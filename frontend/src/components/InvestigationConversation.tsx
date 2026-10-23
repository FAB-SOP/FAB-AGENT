import {LoaderCircle} from 'lucide-react';
import {type Obj,when} from '../api/types';
import {ConclusionNotice,NextStepNotice,StanceBadge} from './AnalysisMarks';
import {answerText,counterIds,nextCheck} from '../domain/analysisMarks';
import {EvidenceList} from './InvestigationContext';
import {statusMessages,ThreadAnswer} from './AnalysisRequestStatus';

/** 요청이 아직 끝나지 않았으면 현재 작업(재개까지 따라간 것) 상태를 보여 준다. 실패는 별도 항목이 있다 */
export function JobProgress({job}:{job?:Obj|null}){
 if(!job||['SUCCEEDED','FAILED','CANCELLED'].includes(job.status))return null;
 const loading=['QUEUED','RUNNING'].includes(job.status);
 const message=job.status==='WAITING_APPROVAL'?'AI가 확인 요청을 제안했습니다. 아래 카드에서 검토하세요.':statusMessages[job.status]||job.status;
 return <p role="status" aria-live="polite" className="analysis-progress">
  {loading&&<LoaderCircle className="loading-spinner" size={20} aria-hidden="true"/>}
  {message}
 </p>;
}
export function AnswerItem({item,latestCauseId}:{item:Obj;latestCauseId?:string|null}){
 const result=item.data?.analysis||{};
 const at=<span className="small muted">{when(item.occurredAt)} KST</span>;
 const cause=result.resultKind==='CAUSE_ANALYSIS';
 const title=item.job?.kind==='BASIC'?'AI 원인 분석':cause?'AI 원인 분석 갱신':'AI 답변';
 if(cause&&latestCauseId&&result.resultId!==latestCauseId){
  return <details className="previous-analysis">
   <summary>이전 {title} · {when(item.occurredAt)} KST</summary>
   <AnalysisResultBody result={result}/>
  </details>;
 }
 return <article className={cause?'conversation-analysis':'qa-answer'}>
  <div className="row-between"><span className="answer-by">{title}</span>{at}</div>
  {cause?<AnalysisResultBody result={result}/>:<ThreadAnswer answer={result}/>}
 </article>;
}
export function AnalysisResultBody({result}:{result:Obj}){
 const evidenceRefs=result.evidenceRefs||[];
 const causes:Obj[]=result.candidateCauses||[];
 const verifies=new Set(causes.map(cause=>cause.verify).filter(Boolean));
 // 모든 후보의 결과 확인 문장이 같으면 한 번만 보여 준다
 const sharedVerify=causes.length>1&&verifies.size===1?[...verifies][0]:'';
 return <div className="conversation-result">
  <ConclusionNotice result={result}/>
  <p className="analysis-summary">{answerText(result)}</p>
  <NextStepNotice result={result}/>
  {result.observedFacts?.length>0&&<details className="conversation-evidence">
   <summary>관측 사실 {result.observedFacts.length}건</summary>
   {result.observedFacts.map((fact:Obj,index:number)=><p key={index}>{fact.statement||fact.text||fact.description||fact.label}</p>)}
  </details>}
  {causes.length>0&&<h4 className="cause-heading">원인 후보 {causes.length}건 <span className="small muted">확정 아님</span></h4>}
  {causes.map((cause:Obj,index:number)=><div className="hypothesis" key={cause.causeId||index}>
   <span className="rank">후보 {cause.rank||index+1}</span>
   <div>
    <h3>{cause.cause||cause.hypothesis||'원인 후보'} <StanceBadge stance={cause.stance}/></h3>
    <EvidenceList ids={cause.supportingEvidence||cause.supportingEvidenceIds||[]} refs={evidenceRefs} label="지지 근거"/>
    <EvidenceList ids={counterIds(cause)} refs={evidenceRefs} label="반대·추가 확인 근거"/>
    {cause.missingData?.length>0&&<p className="small muted">부족한 자료 · {cause.missingData.join(' / ')}</p>}
    {nextCheck(cause)&&<p>확인 항목 · {nextCheck(cause)}</p>}
    {cause.action&&<p>조치 제안 · {cause.action}</p>}
    {cause.verify&&!sharedVerify&&<p>결과 확인 · {cause.verify}</p>}
   </div>
  </div>)}
  {sharedVerify&&<p className="small muted">결과 확인 · {sharedVerify}</p>}
  {result.limitations?.length>0&&<details className="conversation-evidence">
   <summary>분석 참고사항</summary>
   {result.limitations.map((text:string,index:number)=><p key={index}>{text}</p>)}
  </details>}
 </div>;
}
