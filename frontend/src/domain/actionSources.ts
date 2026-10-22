import {type Obj,when} from '../api/types';
export type ActionChoice={
 key:string;
 type:'CAUSE'|'NEXT_ACTION'|'PROPOSAL'|'WORK_ITEM';
 refId:string;
 itemId?:string;
 title:string;
 subtitle:string;
 check?:string;
 action?:string;
 rationale?:string;
};
export const actionKinds:Record<string,string>={
 REVIEW:'검토 기록',
 INSPECTION:'현장 점검',
 MANUAL_MAINTENANCE:'조치 수행',
 OTHER:'기타 기록',
};
export const sourceKey=(source?:Obj|null)=>source?`${source.type}:${source.refId}:${source.itemId??''}`:'';
export const actionSourceKey=(action:Obj)=>
 sourceKey(action.relatedSource)||(action.approvalId?sourceKey({type:'PROPOSAL',refId:action.approvalId}):'');
const analysisKindLabels:Obj={BASIC:'기본 분석',DEEP:'추가 분석',RESUME:'재개 분석'};
type ChoiceInput=Omit<ActionChoice,'key'>;
/** 분석 결과 하나의 원인 후보 · 후속 확인 항목 */
function analysisChoices(result:Obj):ChoiceInput[]{
 const version=`${analysisKindLabels[result.kind]||'분석'} · ${when(result.createdAt)}`;
 const causes=(result.candidateCauses||[]).filter((cause:Obj)=>cause.causeId).map((cause:Obj)=>({
  type:'CAUSE' as const,
  refId:result.resultId,
  itemId:cause.causeId,
  title:cause.hypothesis||cause.cause||'원인 후보',
  subtitle:version+' · 원인 후보',
  check:cause.check||cause.nextChecks?.join(' / '),
  action:cause.action,
 }));
 const nextActions=[...(result.nextActions||[]).entries()].map(([index,item]:[number,Obj])=>({
  type:'NEXT_ACTION' as const,
  refId:result.resultId,
  itemId:String(index),
  title:item.label||item.description||'후속 확인',
  subtitle:version+' · 후속 확인',
 }));
 return [...causes,...nextActions];
}
function proposalChoice(proposal:Obj):ChoiceInput{
 return {
  type:'PROPOSAL',
  refId:proposal.approvalId,
  title:proposal.payload?.question||proposal.payload?.description||proposal.rationale||'요청 제안',
  subtitle:(proposal.source==='AGENT'?'AI 요청 제안':'담당자 요청 제안')+' · '+when(proposal.createdAt),
  rationale:proposal.rationale,
 };
}
/** 이미 기록된 조치가 연결해 둔 항목 — 원본 분석 · 제안을 더 볼 수 없어도 선택지로 남긴다 */
function linkedSourceChoice(action:Obj):ChoiceInput|null{
 const source=action.relatedSource;
 if(!source)return null;
 return {
  type:source.type,
  refId:source.refId,
  itemId:source.itemId,
  title:source.title,
  subtitle:'이전에 연결한 항목',
  check:source.details?.check,
  action:source.details?.action,
  rationale:source.rationale,
 };
}
export function buildActionChoices(results:Obj[],proposals:Obj[],actions:Obj[]=[]):ActionChoice[]{
 const choices=new Map<string,ActionChoice>();
 const add=(choice:ChoiceInput)=>{
  const key=sourceKey(choice);
  if(!choices.has(key))choices.set(key,{...choice,key});
 };
 results.flatMap(analysisChoices).forEach(add);
 proposals.map(proposalChoice).forEach(add);
 for(const action of actions){
  const choice=linkedSourceChoice(action);
  if(choice)add(choice);
 }
 return [...choices.values()];
}
export function incidentStage(incident:Obj,job?:Obj|null):{code:string;label:string}{
 if(incident.status==='CLOSED')return {code:'CLOSED',label:'처리 종료'};
 if(incident.status==='ON_HOLD')return {code:'ON_HOLD',label:'처리 보류'};
 const state=job?.status||incident.activeJob?.status||incident.jobStatus;
 const kind=job?.kind||incident.activeJob?.kind;
 const phase=kind==='BASIC'?'기본 분석':kind==='DEEP'?'추가 분석':kind==='RESUME'?'후속 분석':'분석';
 if(state==='QUEUED')return {code:state,label:phase+' 대기'};
 if(state==='RUNNING')return {code:state,label:phase+' 중'};
 if(state==='WAITING_APPROVAL')return {code:state,label:'제안 검토 대기'};
 if(state==='WAITING_REPLY'||incident.status==='WAITING_REPLY')return {code:'WAITING_REPLY',label:'담당자 회신 대기'};
 if(state==='FAILED')return {code:state,label:'분석 실패'};
 if(state==='SUCCEEDED')return {code:state,label:'분석 완료'};
 return {code:incident.status,label:incident.status==='OPEN'?'이상 접수':'확인·조치 진행'};
}
