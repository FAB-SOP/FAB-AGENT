import {LoaderCircle} from 'lucide-react';
import {approvalKindLabels,type Obj,when} from '../api/types';
import {Badge,Empty} from './ui';
import {ConclusionNotice,StanceBadge} from './AnalysisMarks';
import {answerText,counterIds,nextCheck} from '../domain/analysisMarks';
import {EvidenceList} from './InvestigationContext';
import {statusMessages,ThreadAnswer} from './AnalysisRequestStatus';

/** 사건 대화 — 서버(conversation API)가 준 순서 그대로 그린다. 이전 원인 분석만 접는다 */
export function InvestigationConversation({items,latestCauseId,startingBasic=false,loading=false}:{
 items:Obj[];
 latestCauseId?:string|null;
 startingBasic?:boolean;
 loading?:boolean;
}){
 return <div className="investigation-conversation">
  {items.map(item=><ConversationItem key={item.entryId} item={item} latestCauseId={latestCauseId}/>)}
  {startingBasic&&<div className="reanalysis-progress" role="status">
   <LoaderCircle size={24} className="loading-spinner"/>
   <strong>재분석 요청 중…</strong>
  </div>}
  {!items.length&&!startingBasic&&<Empty>{loading?'조사 내용을 불러오는 중…':'기본 분석 결과를 기다리고 있습니다.'}</Empty>}
 </div>;
}
const requestTitles:Record<string,string>={
 FOLLOWUP_REQUESTED:'선택한 결과로 추가 분석',
 REQUEST_DRAFT_REQUESTED:'확인 요청서 작성 요청',
};
function ConversationItem({item,latestCauseId}:{item:Obj;latestCauseId?:string|null}){
 const at=<span className="small muted">{when(item.occurredAt)} KST</span>;
 switch(item.type){
 case 'BASIC_REQUESTED':
  return <div className="conversation-note">
   <div className="row-between"><strong>{item.actor?.type==='HUMAN'?`재분석 요청 · ${item.actor.displayName}`:'이상 감지 · 기본 분석 시작'}</strong>{at}</div>
   <JobProgress job={item.currentJob}/>
  </div>;
 case 'QUESTION':
 case 'FOLLOWUP_REQUESTED':
 case 'REQUEST_DRAFT_REQUESTED':{
  const inputs:Obj[]=item.data?.followup?.selectedInputs||[];
  return <div className="qa-question">
   <div className="row-between"><strong>{requestTitles[item.type]||'질문'} · {item.actor?.displayName}</strong>{at}</div>
   {(item.data?.followup?.question||item.text)&&<p>{item.data?.followup?.question||item.text}</p>}
   {inputs.length>0&&<details className="conversation-evidence">
    <summary>선택한 결과 {inputs.length}건</summary>
    {inputs.map((input,index)=><p key={index}>{input.content}</p>)}
   </details>}
   <JobProgress job={item.currentJob}/>
  </div>;
 }
 case 'AI_ANSWER':
  return <AnswerItem item={item} latestCauseId={latestCauseId}/>;
 case 'APPROVAL_REQUESTED':{
  const approval=item.data?.approval;
  return <div className="conversation-note">
   <div className="row-between"><strong>{approval?.source==='AGENT'?'AI가 제안':`${item.actor?.displayName} 님이 제안`} · {approvalSubject(approval)}</strong>{at}</div>
   <p>{approval?.payload?.question||item.text}</p>
   {approval?.payload?.question&&item.text&&item.text!==approval.payload.question&&<p className="small muted">이유 · {item.text}</p>}
   {approval?.status==='PENDING'&&<p className="small muted">아래 승인 대기에서 검토하세요.</p>}
  </div>;
 }
 case 'APPROVAL_DECIDED':{
  const approval=item.data?.approval;
  const rejected=item.eventType==='REJECTED';
  return <div className="conversation-note">
   <div className="row-between"><strong>{item.actor?.displayName} 님이 {rejected?'거절':'승인'} · {approvalSubject(approval)}</strong>{at}</div>
   {approval?.payload?.question&&<p className="one-line">{approval.payload.question}</p>}
   {!rejected&&approval?.executionRef?.type==='CHECK_REQUEST'&&<p className="small muted">요청을 담당자에게 보냈습니다.</p>}
   {item.text&&<p className="small">{rejected?'거절 사유':'검토 의견'} · {item.text}</p>}
  </div>;
 }
 case 'REPLY':
  return <div className="conversation-note">
   <div className="row-between"><strong>{item.actor?.displayName} 님의 회신{item.request?.targetProcessCode?` · ${item.request.targetProcessCode}`:''}</strong>{at}</div>
   {item.request?.question&&<p className="small muted one-line">요청 · {item.request.question}</p>}
   <p>{item.text}</p>
   <p className="small muted">아래 결과 검토에서 추가 분석에 반영할 수 있습니다.</p>
  </div>;
 case 'ANALYSIS_FAILED':
  return <div className="conversation-note failed">
   <div className="row-between"><strong>분석 실패</strong>{at}</div>
   <p className="warning-text">{item.job?.error?.message||item.text||'분석에 실패했습니다.'}</p>
  </div>;
 default:
  return null;
 }
}
/** 무엇을 누구에게 — 예: 정비 요청 → CMP */
const approvalSubject=(approval?:Obj)=>{
 const kind=approvalKindLabels[approval?.actionType]||'승인 요청';
 const target=approval?.payload?.targetProcessCode;
 return target?`${kind} → ${target}`:kind;
};
/** 요청이 아직 끝나지 않았으면 현재 작업(재개까지 따라간 것) 상태를 보여 준다. 실패는 별도 항목이 있다 */
function JobProgress({job}:{job?:Obj|null}){
 if(!job||['SUCCEEDED','FAILED','CANCELLED'].includes(job.status))return null;
 const loading=['QUEUED','RUNNING'].includes(job.status);
 const message=job.status==='WAITING_APPROVAL'?'AI가 확인 요청을 제안했습니다. 아래 승인 대기에서 검토하세요.':statusMessages[job.status]||job.status;
 return <p role="status" aria-live="polite" className="analysis-progress">
  {loading&&<LoaderCircle className="loading-spinner" size={20} aria-hidden="true"/>}
  {message}
 </p>;
}
function AnswerItem({item,latestCauseId}:{item:Obj;latestCauseId?:string|null}){
 const result=item.data?.analysis||{};
 const at=<span className="small muted">{when(item.occurredAt)} KST</span>;
 const cause=result.resultKind==='CAUSE_ANALYSIS';
 const title=item.job?.kind==='BASIC'?'기본 분석 결과':cause?'원인 분석 갱신':'AI 답변';
 if(cause&&latestCauseId&&result.resultId!==latestCauseId){
  return <details className="previous-analysis">
   <summary>이전 {title} · {when(item.occurredAt)} KST</summary>
   <AnalysisResultBody result={result}/>
  </details>;
 }
 return <article className={cause?'conversation-analysis':'qa-answer'}>
  <div className="row-between"><h3>{title}</h3>{at}</div>
  {cause?<><AnalysisResultBody result={result}/><Badge value="SUCCEEDED">분석 완료</Badge></>:<ThreadAnswer answer={result}/>}
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
