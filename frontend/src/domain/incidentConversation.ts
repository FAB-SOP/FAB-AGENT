import {type Obj} from '../api/types';
/** 사건 대화 통합 조회 한 페이지 — docs/incident-conversation-api.md, docs/conversation-context-api.md */
export type ConversationPage={
 incidentId:string;
 items:Obj[];
 nextCursor:string|null;
 jobs:Obj[];
 runningJobs:Obj[];
 waitingJobs:Obj[];
 pendingApprovals:Obj[];
 pendingWorkItems:Obj[];
 latestCauseAnalysis:Obj|null;
 investigationResults:Obj[];
};
/** 페이지(최신 페이지가 앞)를 오래된 순 한 목록으로. 겹치면 최신 조회 값 */
export function conversationItems(pages:ConversationPage[]){
 const byId=new Map<string,Obj>();
 for(const page of [...pages].reverse())for(const item of page.items)byId.set(item.entryId,item);
 return [...byId.values()];
}
/** 새 질문·추가 분석은 실행 중인 조사 작업이 있을 때만 막는다(승인 대기는 막지 않음, PR #24) */
export const runningJob=(page?:ConversationPage)=>page?.runningJobs[0];

export type Flow={flowId:string;sourceResultId?:string;items:Obj[]};
export type Block={blockId:string;blockType:string;title:string;createdAt:string;head?:Obj;answers:Obj[];flows:Flow[];notes:Obj[]};
const HEAD_TYPES=['BASIC_REQUESTED','QUESTION','FOLLOWUP_REQUESTED'];
/**
 * 분석·질문 블록(placement.blockId)으로 묶고, 블록 안에서 업무·승인·요청·회신을 흐름(flowId)별 카드로 묶는다.
 * 블록은 시작 시각(placement.createdAt) 순 — 늦은 회신이 와도 원래 블록에 남는다. placement가 없으면 독립 기록.
 */
export function buildBlocks(items:Obj[]):Block[]{
 const blocks=new Map<string,Block&{order:number}>();
 items.forEach((item,index)=>{
  const place=item.placement;
  const id=place?.blockId||'entry:'+item.entryId;
  let block=blocks.get(id);
  if(!block){
   block={blockId:id,blockType:place?.blockType||'DIRECT',title:place?.title||'',createdAt:place?.createdAt||item.occurredAt,
    answers:[],flows:[],notes:[],order:index};
   blocks.set(id,block);
  }
  if(HEAD_TYPES.includes(item.type)&&!block.head)block.head=item;
  else if(item.type==='AI_ANSWER')block.answers.push(item);
  else if(place?.flowId){
   let flow=block.flows.find(other=>other.flowId===place.flowId);
   if(!flow)block.flows.push(flow={flowId:place.flowId,sourceResultId:item.links?.sourceResultId||undefined,items:[]});
   flow.items.push(item);
  }else block.notes.push(item);
 });
 return [...blocks.values()].sort((a,b)=>Date.parse(a.createdAt)-Date.parse(b.createdAt)||a.order-b.order);
}
/** 흐름 카드 하나의 현재 모습 — 시작 항목, 현재 업무·승인 상태, 요청별 진행, 회신·작업 결과 */
export function flowView(flow:Flow){
 const work=[...flow.items].reverse().find(item=>item.data?.work)?.data.work;
 const approval=[...flow.items].reverse().find(item=>item.data?.approval)?.data.approval;
 const requestIds=[...new Set(flow.items.map(item=>item.links?.requestId).filter(Boolean))] as string[];
 const requests=requestIds.map(requestId=>{
  const own=flow.items.filter(item=>item.links?.requestId===requestId);
  return {
   requestId,
   request:own.find(item=>item.data?.request)?.data.request,
   replies:own.filter(item=>item.type==='REPLY'),
  };
 });
 return {
  start:flow.items[0],
  work,
  approval,
  requests,
  results:flow.items.filter(item=>['REPLY','ACTION_RESULT'].includes(item.type)),
  history:flow.items.filter(item=>stepLabel(item)),
 };
}
const steps:Record<string,string>={
 REQUEST_SENT:'발송',REQUEST_ACCEPTED:'수락',REQUEST_REJECTED:'요청 거절',REQUEST_WITHDRAWN:'요청 철회',
 WORK_ADOPTED:'채택',WORK_CLOSED_UNPERFORMED:'미수행 종결',
};
/** 흐름의 단계 이름. 상태를 하나의 '완료'로 바꾸지 않고 기록 종류대로 */
export function stepLabel(item:Obj):string{
 if(item.type==='APPROVAL_DECIDED')return item.eventType==='REJECTED'?'승인 거절':'승인';
 if(item.type==='WORK_EVENT'){
  if(item.eventType==='WORK_DECIDED')return /REJECT/.test(item.text||'')?'관리자 반려':/DECLINE/.test(item.text||'')?'채택 안 함':'관리자 승인';
  return steps[item.eventType]||'';
 }
 return steps[item.type]||'';
}
/** AI 추천 번호 — 같은 답변에서 나온 AI 업무 제안을 1부터 */
export function suggestionNumbers(flows:Flow[]){
 const numbers=new Map<string,number>();
 const counts=new Map<string,number>();
 for(const flow of flows){
  const start=flow.items[0];
  if(start?.type!=='WORK_PROPOSED'||start.data?.work?.createdBy!=='agent')continue;
  const key=flow.sourceResultId||'';
  counts.set(key,(counts.get(key)||0)+1);
  numbers.set(flow.flowId,counts.get(key)!);
 }
 return numbers;
}
/** 회신·작업 결과를 추가 분석에 고를 수 있는지 — 서버 analysisUse로만 판단 */
export function selectState(item:Obj):{selectable:boolean;label:string}{
 const use=item.analysisUse||{};
 if(use.activeRequestIds?.length)return {selectable:false,label:'추가 분석 진행 중'};
 if(use.successfulCount>0)return {selectable:false,label:'추가 분석 완료 · 다시 고를 수 없습니다'};
 return {selectable:!!item.sourceRef,label:'이 결과를 추가 분석에 선택'};
}
/** 아직 불러오지 않은 이전 기록에 남은 승인·업무 대기 */
export function pendingOutsidePages(items:Obj[],page?:ConversationPage){
 const approvals=new Set(items.map(item=>item.links?.approvalId).filter(Boolean));
 const tasks=new Set(items.map(item=>item.links?.taskId||item.data?.work?.taskId).filter(Boolean));
 return (page?.pendingApprovals.filter(approval=>!approvals.has(approval.approvalId)).length||0)
  +(page?.pendingWorkItems.filter(work=>!tasks.has(work.taskId)).length||0);
}
