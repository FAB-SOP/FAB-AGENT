import {type Obj} from '../api/types';
/** 질문과 기본/재분석 요청을 제출 순서로 병합한다. 결과 도착 순서로 재정렬하지 않는다. */
export function conversationEntries(timeline:Obj[]=[],history:Obj[]=[],activeJob?:Obj|null):Obj[]{
 const entries=new Map<string,Obj>();
 for(const item of timeline){
  if(!['QUESTION_ASKED','ANALYSIS_RERUN'].includes(item.type)||item.ref?.type!=='JOB'||!item.ref.id)continue;
  entries.set(item.ref.id,{
   jobId:item.ref.id,
   kind:item.type==='QUESTION_ASKED'?'DEEP':'BASIC',
   rerun:item.type==='ANALYSIS_RERUN',
   question:String(item.message||'').replace(/^추가 조사 질문:\s*/,''),
   createdAt:item.at,
  });
 }
 for(const result of history){
  if(result.kind==='BASIC'&&!entries.has(result.jobId)){
   entries.set(result.jobId,{jobId:result.jobId,kind:'BASIC',createdAt:result.createdAt});
  }
 }
 if(activeJob?.kind==='BASIC'&&!entries.has(activeJob.jobId)){
  entries.set(activeJob.jobId,{
   jobId:activeJob.jobId,
   kind:'BASIC',
   rerun:history.some(result=>result.kind==='BASIC'),
   createdAt:activeJob.createdAt||new Date().toISOString(),
  });
 }
 return [...entries.values()].sort((first,second)=>Date.parse(first.createdAt)-Date.parse(second.createdAt));
}
/**
 * 결과 검토에서 요청한 추가 분석은 질문 기록이 없어 결과로 대화 항목을 만든다.
 * 승인 대기 후 재개(RESUME)로 끝난 결과도 같은 스레드(threadId)끼리 한 항목으로 묶고,
 * 이미 질문·기본 분석 스레드에 들어 있는 작업·스레드는 건너뛴다.
 */
export function followupEntries(results:Obj[],coveredJobIds:Set<string>,coveredThreadIds:Set<string>):Obj[]{
 const byThread=new Map<string,Obj[]>();
 for(const result of results){
  if(!['DEEP','RESUME'].includes(result.kind)||!result.jobId||coveredJobIds.has(result.jobId))continue;
  const key=result.threadId||result.jobId;
  if(coveredThreadIds.has(key))continue;
  byThread.set(key,[...(byThread.get(key)||[]),result]);
 }
 return [...byThread.values()].map(group=>{
  const first=[...group].sort((a,b)=>Date.parse(a.createdAt)-Date.parse(b.createdAt))[0];
  return {jobId:first.jobId,kind:'DEEP',followup:true,question:'선택한 결과를 반영한 추가 분석',createdAt:first.createdAt};
 });
}
