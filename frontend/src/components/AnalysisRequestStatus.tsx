import {ConclusionNotice,ResultKindTag,StanceBadge} from './AnalysisMarks';
import {answerText,nextCheck} from '../domain/analysisMarks';
import {LoaderCircle} from 'lucide-react';
import {Link} from 'react-router-dom';
import {type Obj,when} from '../api/types';
import {Badge,ErrorMessage,Panel} from './ui';
import {PayloadDetails} from './InvestigationContext';

export const statusMessages:Record<string,string>={
 QUEUED:'분석 요청을 접수했습니다.',
 RUNNING:'센서 데이터와 문서를 분석하고 있습니다.',
 WAITING_APPROVAL:'AI가 확인 요청을 제안했습니다. 실행할 내용을 검토해 주세요.',
 WAITING_REPLY:'담당자에게 확인을 요청했습니다. 회신 후 분석이 이어집니다.',
 SUCCEEDED:'요청한 분석이 완료되었습니다.',
 FAILED:'분석에 실패했습니다.',
 CANCELLED:'분석 요청이 취소되었습니다.',
};
const approvalLink=(approvalId:string)=>'/approvals?approval='+encodeURIComponent(approvalId);
export function isPreviousAnalysis(job:Obj|null|undefined,result:Obj|null|undefined){
 return !!(job&&result&&['BASIC','DEEP','RESUME'].includes(job.kind)&&job.jobId!==result.jobId);
}
export function AnalysisRequestStatus({job,approval,error}:{job?:Obj;approval?:Obj;error?:unknown}){
 if(!job)return null;
 const approvalId=job.waitingRef?.type==='APPROVAL'?job.waitingRef.id:null;
 const loading=['QUEUED','RUNNING'].includes(job.status);
 const statusMessage=job.status==='RUNNING'&&job.kind==='DEEP'
  ?'질문을 분석하고 답변을 준비하고 있습니다.'
  :statusMessages[job.status]||job.status;
 return <Panel title="요청한 분석" extra={<Badge value={job.status}/>} className="analysis-request-status">
  {job.question&&<blockquote className="request-question">{job.question}</blockquote>}
  <p role="status" aria-live="polite" className="analysis-progress">
   {loading&&<LoaderCircle className="loading-spinner" size={22} aria-hidden="true"/>}
   {statusMessage}
  </p>
  {job.createdAt&&<p className="small muted">요청 시각 · {when(job.createdAt)} KST</p>}
  {job.error?.message&&<p className="warning-text">{job.error.message}</p>}
  {error&&<ErrorMessage error={error}/>}
  {job.status==='WAITING_APPROVAL'&&approval&&<div className="request-proposal">
   <h3>AI 제안 이유</h3>
   <p>{approval.rationale||approval.payload?.brief?.reason}</p>
   {approval.payload?.question&&<p><strong>확인할 내용 · </strong>{approval.payload.question}</p>}
   <details>
    <summary>제안 근거·확인 항목</summary>
    <PayloadDetails value={approval.payload}/>
   </details>
  </div>}
  {job.status==='WAITING_APPROVAL'&&approvalId&&<Link className="button" to={approvalLink(approvalId)}>제안 검토하기</Link>}
  {job.status==='WAITING_REPLY'&&<Link className="text-button" to="/check-requests">확인 요청 보기</Link>}
 </Panel>;
}

/** 질문에 대한 AI 답 — 원인 분석을 갱신했으면 태그·결론 변경을 함께 */
export function ThreadAnswer({answer}:{answer:Obj}){
 return <div className="qa-result">
  <ResultKindTag result={answer}/>
  <ConclusionNotice result={answer}/>
  <p>{answerText(answer)}</p>
  {answer.candidateCauses?.map((cause:Obj,index:number)=><div key={cause.candidateId||cause.causeId||index}>
   <h3>{cause.rank||cause.priority}. {cause.hypothesis||cause.cause} <StanceBadge stance={cause.stance}/></h3>
   {nextCheck(cause)&&<p>{nextCheck(cause)}</p>}
   {cause.action&&<p>조치 제안 · {cause.action}</p>}
  </div>)}
 </div>;
}
