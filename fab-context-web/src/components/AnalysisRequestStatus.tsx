import {LoaderCircle} from 'lucide-react';
import {Link} from 'react-router-dom';
import {type QuestionThread} from '../domain/questionThreads';
import {type Obj,when} from '../api/types';
import {Badge,ErrorMessage,Panel} from './ui';
import {PayloadDetails} from './InvestigationContext';

const statusMessages:Record<string,string>={
 QUEUED:'분석 요청을 접수했습니다.',
 RUNNING:'센서 데이터와 문서를 분석하고 있습니다.',
 WAITING_APPROVAL:'AI가 확인 요청을 제안했습니다. 실행할 내용을 검토해 주세요.',
 WAITING_REPLY:'담당자에게 확인을 요청했습니다. 회신 후 분석이 이어집니다.',
 SUCCEEDED:'요청한 분석이 완료되었습니다.',
 FAILED:'분석에 실패했습니다.',
 CANCELLED:'분석 요청이 취소되었습니다.',
};
export function isPreviousAnalysis(job:Obj|null|undefined,result:Obj|null|undefined){
 return !!(job&&result&&['BASIC','DEEP','RESUME'].includes(job.kind)&&job.jobId!==result.jobId);
}
export function AnalysisRequestStatus({job,approval,error}:{job?:Obj;approval?:Obj;error?:unknown}){
 if(!job)return null;
 const approvalId=job.waitingRef?.type==='APPROVAL'?job.waitingRef.id:null;
 const loading=['QUEUED','RUNNING'].includes(job.status);
 const statusMessage=job.status==='RUNNING'&&job.kind==='DEEP'?'질문을 분석하고 답변을 준비하고 있습니다.':statusMessages[job.status]||job.status;
 return <Panel title="요청한 분석" extra={<Badge value={job.status}/>} className="analysis-request-status">
  {job.question&&<blockquote className="request-question">{job.question}</blockquote>}
  <p role="status" aria-live="polite" className="analysis-progress">{loading&&<LoaderCircle className="loading-spinner" size={22} aria-hidden="true"/>}{statusMessage}</p>
  {job.createdAt&&<p className="small muted">요청 시각 · {when(job.createdAt)} KST</p>}
  {job.error?.message&&<p className="warning-text">{job.error.message}</p>}
  {error&&<ErrorMessage error={error}/>}
  {job.status==='WAITING_APPROVAL'&&approval&&<div className="request-proposal">
   <h3>AI 제안 이유</h3><p>{approval.rationale||approval.payload?.brief?.reason}</p>
   {approval.payload?.question&&<p><strong>확인할 내용 · </strong>{approval.payload.question}</p>}
   <details><summary>제안 근거·확인 항목</summary><PayloadDetails value={approval.payload}/></details>
  </div>}
  {job.status==='WAITING_APPROVAL'&&approvalId&&<Link className="button" to={'/approvals?approval='+encodeURIComponent(approvalId)}>제안 검토하기</Link>}
  {job.status==='WAITING_REPLY'&&<Link className="text-button" to="/check-requests">확인 요청 보기</Link>}
 </Panel>;
}

export function questionEntries(timeline:Obj[]=[]){
 return timeline.filter(item=>item.type==='QUESTION_ASKED'&&item.ref?.type==='JOB'&&item.ref.id).map(item=>({jobId:item.ref.id,question:String(item.message||'').replace(/^추가 조사 질문:\s*/,''),createdAt:item.at}));
}
export function QuestionHistory({entries,jobs=[],threads=[],results=[],proposal}:{entries:Obj[];jobs?:(Obj|undefined)[];threads?:(QuestionThread|undefined)[];results?:Obj[];proposal?:Obj}){
 if(!entries.length)return null;
 return <Panel title="질문·답변"><div className="question-history">{entries.map((entry,index)=>{
  const thread=threads[index];const job=thread?.latestJob||jobs[index];
  const jobIds=new Set(thread?.jobs.map(item=>item.jobId)||[entry.jobId]);
  const answers=results.filter(result=>jobIds.has(result.jobId)||(job?.resultRef?.id&&result.resultId===job.resultRef.id)).sort((a,b)=>Date.parse(a.createdAt||'')-Date.parse(b.createdAt||''));
  const pendingProposal=job?.status==='WAITING_APPROVAL'&&proposal?.approvalId===job.waitingRef?.id?proposal:undefined;
  const loading=job&&['QUEUED','RUNNING'].includes(job.status);
  return <article className="question-thread" key={entry.jobId}>
   <div className="qa-question"><div className="row-between"><strong>질문</strong><span className="small muted">{when(entry.createdAt)} KST</span></div><p>{thread?.rootJob.question||job?.question||entry.question}</p></div>
   <div className="qa-answer"><div className="row-between"><strong>AI 답변</strong>{job&&<Badge value={job.status}/>}</div>
    {answers.map(answer=><div className="qa-result" key={answer.resultId||answer.jobId}><p>{answer.summary}</p>{answer.candidateCauses?.map((cause:Obj)=><div key={cause.causeId}><h3>{cause.rank||cause.priority}. {cause.hypothesis||cause.cause}</h3>{cause.check&&<p>{cause.check}</p>}{cause.action&&<p>조치 제안 · {cause.action}</p>}</div>)}</div>)}
    {pendingProposal&&<div className="qa-proposal"><h3>확인 요청 제안</h3><p>{pendingProposal.rationale}</p>{pendingProposal.payload?.question&&<p>{pendingProposal.payload.question}</p>}<Link className="text-button" to={'/approvals?approval='+encodeURIComponent(pendingProposal.approvalId)}>제안 검토하기</Link></div>}
    {(!answers.length||job?.status!=='SUCCEEDED')&&<p role="status" className="analysis-progress">{loading&&<LoaderCircle className="loading-spinner" size={20} aria-hidden="true"/>}{job?.resultRef?.type==='APPROVAL_REJECTED'?'제안이 거절되어 해당 조사를 마쳤습니다.':job?statusMessages[job.status]||job.status:'처리 상태를 불러오는 중…'}</p>}
    {job?.error?.message&&<p className="warning-text">{job.error.message}</p>}
   </div>
  </article>;
 })}</div></Panel>;
}
