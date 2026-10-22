import {type Obj} from '../api/types';
export type QuestionThread={rootJob:Obj;latestJob:Obj;jobs:Obj[]};
/** 승인·회신으로 생성된 RESUME 작업도 최초 질문에 연결한다. 조회만 수행한다. */
export async function loadQuestionThread(jobId:string,fetchJob:(id:string)=>Promise<Obj>):Promise<QuestionThread>{
 const jobs:Obj[]=[];const visited=new Set<string>();let next:string|undefined=jobId;
 while(next){
  if(visited.has(next))throw new Error('분석 작업 연결에 순환이 있습니다.');
  visited.add(next);const job=await fetchJob(next);jobs.push(job);
  next=job.resultRef?.type==='RESUME_JOB'?job.resultRef.id:undefined;
 }
 return {rootJob:jobs[0],latestJob:jobs[jobs.length-1],jobs};
}
