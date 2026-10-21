import {type Obj} from '../api/types';
/** 질문과 기본/재분석 요청을 제출 순서로 병합한다. 결과 도착 순서로 재정렬하지 않는다. */
export function conversationEntries(timeline:Obj[]=[],history:Obj[]=[],activeJob?:Obj|null):Obj[]{
 const entries=new Map<string,Obj>();
 for(const item of timeline){
  if(!['QUESTION_ASKED','ANALYSIS_RERUN'].includes(item.type)||item.ref?.type!=='JOB'||!item.ref.id)continue;
  entries.set(item.ref.id,{jobId:item.ref.id,kind:item.type==='QUESTION_ASKED'?'DEEP':'BASIC',rerun:item.type==='ANALYSIS_RERUN',question:String(item.message||'').replace(/^추가 조사 질문:\s*/,''),createdAt:item.at});
 }
 for(const result of history){
  if(result.kind==='BASIC'&&!entries.has(result.jobId))entries.set(result.jobId,{jobId:result.jobId,kind:'BASIC',createdAt:result.createdAt});
 }
 if(activeJob?.kind==='BASIC'&&!entries.has(activeJob.jobId))entries.set(activeJob.jobId,{jobId:activeJob.jobId,kind:'BASIC',rerun:history.some(result=>result.kind==='BASIC'),createdAt:activeJob.createdAt||new Date().toISOString()});
 return [...entries.values()].sort((a,b)=>Date.parse(a.createdAt)-Date.parse(b.createdAt));
}
