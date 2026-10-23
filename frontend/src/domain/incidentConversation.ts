import {type Obj} from '../api/types';
/** 사건 대화 통합 조회 한 페이지 — docs/incident-conversation-api.md */
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
/** 대화에 그리는 종류. 업무·요청·작업 결과는 아래 칸(추천 조치안·결과 검토)에서만 보여 준다 */
const CONVERSATION_TYPES=new Set([
 'BASIC_REQUESTED','QUESTION','FOLLOWUP_REQUESTED','REQUEST_DRAFT_REQUESTED','AI_ANSWER',
 'APPROVAL_REQUESTED','APPROVAL_DECIDED','REPLY','ANALYSIS_FAILED',
]);
/** 페이지(최신 페이지가 앞)를 오래된 순 한 목록으로 — 다시 묶지 않고 서버 순서 그대로, 겹치면 최신 조회 값 */
export function conversationItems(pages:ConversationPage[]){
 const byId=new Map<string,Obj>();
 for(const page of [...pages].reverse())for(const item of page.items)byId.set(item.entryId,item);
 const all=[...byId.values()];
 // 회신에는 무엇에 대한 회신인지 원래 요청(대화에는 안 그림)을 붙인다
 const requests=new Map(all.filter(item=>item.type==='REQUEST_SENT').map(item=>[item.links?.requestId,item.data?.request]));
 return all.filter(item=>CONVERSATION_TYPES.has(item.type))
  .map(item=>item.type==='REPLY'?{...item,request:requests.get(item.links?.requestId)}:item);
}
/** 대화 속 AI 분석 결과 — 추천 조치안을 분석별로 묶을 때 쓴다 */
export const conversationResults=(items:Obj[],latest?:Obj|null)=>{
 const results=items.filter(item=>item.type==='AI_ANSWER'&&item.data?.analysis).map(item=>item.data.analysis);
 return latest&&!results.some(result=>result.resultId===latest.resultId)?[latest,...results]:results;
};
/** 새 질문·추가 분석은 실행 중인 조사 작업이 있을 때만 막는다(승인 대기는 막지 않음, PR #24) */
export const runningJob=(page?:ConversationPage)=>page?.runningJobs[0];
