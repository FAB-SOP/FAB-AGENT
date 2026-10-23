import {useState} from 'react';
import {LoaderCircle} from 'lucide-react';
import {approvalKindLabels,type Incident,type Obj,when} from '../api/types';
import {Empty} from './ui';
import {AnswerItem,JobProgress} from './InvestigationConversation';
import {BriefDetails} from './InvestigationContext';
import {WorkDialog,adoptLabel,workRoutes,workStates} from './WorkItems';
import {refKey} from './ResultReview';
import {ApprovalReviewButton} from '../pages/Approvals';
import {type Block,type Flow,flowView,selectState,stepLabel,suggestionNumbers} from '../domain/incidentConversation';

type Selection={selected:string[];onToggle:(key:string)=>void};
type CardContext=Selection&{incident:Incident;canManage:boolean};
/** 조사 내용 — 분석·질문 블록마다 AI 답과 그 답에서 나온 제안·승인·요청·회신을 한 카드로 이어 보여 준다 */
export function InvestigationBlocks({blocks,latestCauseId,startingBasic=false,loading=false,...context}:CardContext&{
 blocks:Block[];latestCauseId?:string|null;startingBasic?:boolean;loading?:boolean;
}){
 return <div className="investigation-blocks">
  {blocks.map((block,index)=><BlockView key={block.blockId} block={block} index={index+1} latestCauseId={latestCauseId} context={context}/>)}
  {startingBasic&&<div className="reanalysis-progress" role="status">
   <LoaderCircle size={24} className="loading-spinner"/>
   <strong>재분석 요청 중…</strong>
  </div>}
  {!blocks.length&&!startingBasic&&<Empty>{loading?'조사 내용을 불러오는 중…':'기본 분석 결과를 기다리고 있습니다.'}</Empty>}
 </div>;
}
function blockTitle(block:Block){
 if(block.blockType==='BASIC')return block.head?.actor?.type==='HUMAN'?'재분석':'기본 분석';
 if(block.blockType==='QUESTION')return '추가 질문';
 if(block.blockType==='FOLLOWUP')return '선택 결과 추가 분석';
 return '직접 진행';
}
function BlockView({block,index,latestCauseId,context}:{block:Block;index:number;latestCauseId?:string|null;context:CardContext}){
 const numbers=suggestionNumbers(block.flows);
 const answerIds=new Set(block.answers.map(answer=>answer.data?.analysis?.resultId));
 const flowsOf=(resultId?:string)=>block.flows.filter(flow=>resultId?flow.sourceResultId===resultId:!answerIds.has(flow.sourceResultId));
 const cards=(flows:Flow[])=>flows.length>0&&<div className="flow-list">
  {flows.map(flow=><FlowCard key={flow.flowId} flow={flow} number={numbers.get(flow.flowId)} context={context}/>)}
 </div>;
 const inputs:Obj[]=block.head?.data?.followup?.selectedInputs||[];
 return <section className="inv-block">
  <div className="row-between">
   <h3><span className="block-index">{index}</span>{blockTitle(block)}</h3>
   <span className="small muted">{when(block.createdAt)} KST</span>
  </div>
  {block.blockType==='BASIC'&&block.head?.actor?.type==='HUMAN'&&<p className="small muted">{block.head.actor.displayName} 님이 요청</p>}
  {block.blockType==='QUESTION'&&<p className="block-question">{block.head?.text||block.title}</p>}
  {block.blockType==='FOLLOWUP'&&<>
   {inputs.length>0&&<p className="small muted">선택 입력 · {inputs.map(input=>input.content).join(' / ')}</p>}
   {block.head?.data?.followup?.question&&<p className="block-question">{block.head.data.followup.question}</p>}
  </>}
  {block.blockType==='DIRECT'&&block.title&&<p className="small muted">AI 답변과 연결되지 않은 직접 진행 기록입니다.</p>}
  <JobProgress job={block.head?.currentJob}/>
  {block.notes.map(note=><Note key={note.entryId} item={note}/>)}
  <div className="block-body">
   {block.answers.map(answer=><div key={answer.entryId}>
    <AnswerItem item={answer} latestCauseId={latestCauseId}/>
    {cards(flowsOf(answer.data?.analysis?.resultId))}
   </div>)}
   {cards(flowsOf())}
  </div>
 </section>;
}
function Note({item}:{item:Obj}){
 if(item.type==='ANALYSIS_FAILED')return <p className="warning-text">분석 실패 · {item.job?.error?.message||item.text||'분석에 실패했습니다.'}</p>;
 if(item.type==='REQUEST_DRAFT_REQUESTED')return <p className="small muted">{item.actor?.displayName} 님이 확인 요청서 작성을 요청 · {when(item.occurredAt)}</p>;
 return null;
}
const approvalStates:Record<string,string>={PENDING:'승인 대기',APPROVED:'승인됨',REJECTED:'거절됨',EXPIRED:'만료됨'};
/** 카드 상단 상태 — 끝난·멈춘 업무는 업무 상태, 요청이 나갔으면 회신 상태, 아니면 업무·승인 상태 */
function cardState(view:ReturnType<typeof flowView>){
 const {work,approval,requests}=view;
 if(work&&['PROPOSED','DECLINED','REJECTED','CLOSED_UNPERFORMED','WAITING_MANAGER'].includes(work.status))return workStates[work.status];
 if(requests.length){
  if(requests.every(request=>request.replies.length))return '회신 완료';
  return requests.some(request=>request.request?.status==='ACCEPTED')?'수락 · 회신 대기':'회신 대기';
 }
 if(work)return workStates[work.status];
 return approvalStates[approval?.status]||'';
}
function FlowCard({flow,number,context}:{flow:Flow;number?:number;context:CardContext}){
 const [mode,setMode]=useState('');
 const view=flowView(flow);
 const {work,approval,requests,results,history}=view;
 const closed=context.incident.status==='CLOSED';
 const route=work
  ?`${number?`추천 ${number}`:work.createdBy==='agent'?'AI 제안':'직접 제안'} · ${workRoutes[work.route]||work.route}`
  :approval?.source==='AGENT'?'AI 확인 요청서 · 사람 승인':`직접 제안 · ${approvalKindLabels[approval?.actionType]||'승인 요청'}`;
 const title=work?.title||approval?.payload?.question||view.start.text;
 const target=requests[0]?.request||approval?.payload;
 const state=cardState(view);
 const muted=work&&['DECLINED','REJECTED','CLOSED_UNPERFORMED'].includes(work.status)||approval?.status==='REJECTED';
 return <article className={'flow-card'+(muted?' muted':'')}>
  <div className="flow-head">
   <div className="row-between"><span className="flow-route">{route}</span>{state&&<span className={'flow-state'+(/대기/.test(state)?' waiting':'')}>{state}</span>}</div>
   <h4>{title}</h4>
   {target?.targetProcessCode&&<p className="small muted">받는 곳 · {[target.targetProcessCode,target.equipmentId].filter(Boolean).join(' · ')}</p>}
   {work?.reason&&<p className="small">{work.reason}</p>}
   {!work&&approval?.rationale&&approval.rationale!==title&&<p className="small muted">이유 · {approval.rationale}</p>}
   {work?.decisionComment&&<p className="small">검토 의견 · {work.decisionComment}</p>}
   {work?.closeReason&&<p className="small">미수행 사유 · {work.closeReason}</p>}
   {work?.brief&&<details className="work-brief"><summary>확인 배경(미확정)</summary><BriefDetails brief={work.brief}/></details>}
   {work?.status==='PROPOSED'&&<div className="work-buttons">
    <button className="button" disabled={!context.canManage||closed} onClick={()=>setMode('adopt')}>{adoptLabel(work)}</button>
    <button className="button secondary" disabled={!context.canManage||closed} onClick={()=>setMode('decline')}>채택 안 함</button>
   </div>}
   {approval?.status==='PENDING'&&<div className="work-buttons"><ApprovalReviewButton approvalId={approval.approvalId} disabled={!context.canManage||closed}/></div>}
  </div>
  {history.length>0&&<p className="flow-history">{history.map(item=>`${when(item.occurredAt)} ${item.actor?.displayName||''} ${stepLabel(item)}`).join(' → ')}</p>}
  {results.map(item=><ResultView key={item.entryId} item={item} showRequest={requests.length>1} context={context}/>)}
  {requests.some(request=>!request.replies.length)&&<p className="flow-waiting">회신이 오면 이 요청 바로 아래에 표시됩니다.</p>}
  {mode&&work&&<WorkDialog item={work} mode={mode} incident={context.incident} onClose={()=>setMode('')}/>}
 </article>;
}
/** 회신·작업 결과 — 추가 분석에 고를 수 있다. 이미 분석에 쓰였으면 다시 고를 수 없다 */
function ResultView({item,showRequest,context}:{item:Obj;showRequest:boolean;context:CardContext}){
 const key=item.sourceRef?refKey(item.sourceRef):'';
 const select=selectState(item);
 return <div className="flow-result">
  <div className="row-between">
   <strong className="small">{item.actor?.displayName} 님의 {item.type==='REPLY'?'회신':'작업 결과'}</strong>
   <span className="small muted">{when(item.occurredAt)}</span>
  </div>
  {showRequest&&item.requestSummary?.question&&<p className="small muted">요청 · {item.requestSummary.question}</p>}
  <p>{item.text}</p>
  <p className="small muted">담당자가 작성한 확인 내용이며 원인 확정이 아닙니다.</p>
  {key&&<label className="flow-pick">
   <input type="checkbox" checked={select.selectable&&context.selected.includes(key)} disabled={!select.selectable||!context.canManage}
    onChange={()=>context.onToggle(key)}/>
   <span className="small">{select.label}</span>
  </label>}
 </div>;
}
