import {LoaderCircle} from 'lucide-react';
import {type Obj,when} from '../api/types';
import {type QuestionThread} from '../domain/questionThreads';
import {Badge,Empty} from './ui';
import {EvidenceList} from './InvestigationContext';
import {AnalysisRequestStatus,QuestionHistory} from './AnalysisRequestStatus';

export function InvestigationConversation({entries,threads,results,activeJob,proposal,startingBasic=false}:{entries:Obj[];threads:(QuestionThread|undefined)[];results:Obj[];activeJob?:Obj|null;proposal?:Obj;startingBasic?:boolean}){
 return <div className="investigation-conversation">{entries.map((entry,index)=>{
  const thread=threads[index];const job=activeJob?.jobId===entry.jobId?activeJob:thread?.latestJob;
  if(entry.kind==='DEEP')return <QuestionHistory key={entry.jobId} entries={[entry]} threads={[thread]} results={results} proposal={proposal} embedded/>;
  const ids=new Set(thread?.jobs.map(item=>item.jobId)||[entry.jobId]);
  const answers=results.filter(result=>ids.has(result.jobId)).sort((a,b)=>Date.parse(a.createdAt)-Date.parse(b.createdAt));
  const pending=job&&['QUEUED','RUNNING'].includes(job.status);
  return <article className={'conversation-analysis'+(pending?' is-running':'')} key={entry.jobId}>
   <div className="row-between"><h3>{entry.rerun?'재분석':'기본 분석'}</h3><span className="small muted">{when(entry.createdAt)} KST</span></div>
   {pending&&<div className="reanalysis-progress" role="status" aria-live="polite"><LoaderCircle size={24} className="loading-spinner"/><div><strong>{entry.rerun?'재분석 중…':'기본 분석 중…'}</strong><p>센서 데이터와 근거를 다시 확인하고 있습니다.</p></div></div>}
   {answers.map(answer=><AnalysisResultBody key={answer.resultId} result={answer}/>)}
   {job&&!['QUEUED','RUNNING','SUCCEEDED'].includes(job.status)&&<AnalysisRequestStatus job={job} approval={proposal}/>}
   {!answers.length&&!pending&&(!job||job.status==='SUCCEEDED')&&<Empty>분석 결과를 불러오는 중…</Empty>}
   {answers.length>0&&<Badge value="SUCCEEDED">분석 완료</Badge>}
  </article>;
 })}{startingBasic&&<div className="reanalysis-progress" role="status"><LoaderCircle size={24} className="loading-spinner"/><strong>재분석 요청 중…</strong></div>}{!entries.length&&!startingBasic&&<Empty>기본 분석 결과를 기다리고 있습니다.</Empty>}</div>;
}
function AnalysisResultBody({result:a}:{result:Obj}){
 return <div className="conversation-result"><p className="analysis-summary">{a.summary}</p>{a.observedFacts?.length>0&&<details className="conversation-evidence"><summary>관측 사실 {a.observedFacts.length}건</summary>{a.observedFacts.map((fact:Obj,index:number)=><p key={index}>{fact.statement||fact.text||fact.description||fact.label}</p>)}</details>}{(a.candidateCauses||[]).map((cause:Obj,index:number)=><div className="hypothesis" key={cause.causeId||index}><span className="rank">{cause.rank||index+1}</span><div><h3>{cause.cause||cause.hypothesis||'원인 후보'}</h3><EvidenceList ids={cause.supportingEvidence||cause.supportingEvidenceIds||[]} refs={a.evidenceRefs||[]} label="지지 근거"/><EvidenceList ids={cause.counterEvidence||cause.counterEvidenceIds||[]} refs={a.evidenceRefs||[]} label="반대·추가 확인 근거"/>{cause.missingData?.length>0&&<p className="small muted">부족한 자료 · {cause.missingData.join(' / ')}</p>}{cause.check&&<p>확인 항목 · {cause.check}</p>}{cause.action&&<p>조치 제안 · {cause.action}</p>}{cause.verify&&<p>결과 확인 · {cause.verify}</p>}</div></div>)}{a.limitations?.length>0&&<details className="conversation-evidence"><summary>분석 참고사항</summary>{a.limitations.map((text:string,index:number)=><p key={index}>{text}</p>)}</details>}</div>;
}
