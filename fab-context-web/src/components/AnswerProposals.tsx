import {useState} from 'react';
import {Check} from 'lucide-react';
import {write} from '../api/client';
import {type Incident,type Obj} from '../api/types';
import {WorkDialog,useIncidentWork,usePolicy,useWorkCommand,workRoutes,workStates,workText} from './WorkItems';
import {Badge,ErrorMessage,Field,Modal,Notice} from './ui';

/** 답변 안에서 제안을 수락할 때 필요한 사건·권한. 없으면 제안을 읽기 전용으로 보여 준다. */
export type AnswerWork={incident:Incident;canManage:boolean};
type Answer=AnswerWork&{result:Obj};
const adoption=(item:Obj)=>write(`/api/work-items/${item.taskId}/adoptions`,{expectedVersion:item.version,ownerUserId:'',targetProcessCode:item.processCode});
/** 답변의 제안 바로 아래에서 수락·채택 안 함을 처리한다. 내 확인 업무는 바로 추가하고, 관리자 승인·다른 팀 요청은 검토 화면을 거친다. */
function ProposalActions({item,incident,canManage}:{item:Obj;incident:Incident;canManage:boolean}){
 const [mode,setMode]=useState<string|null>(null);const cmd=useWorkCommand(()=>{});const locked=!canManage||incident.status==='CLOSED'||cmd.isPending;
 if(item.status!=='PROPOSED')return <div className="answer-proposal-state"><Badge value={item.status}>{workStates[item.status]}</Badge><span className="small muted">{workRoutes[item.route]} · 검토·조치·효과 탭에서 진행</span></div>;
 return <><div className="work-buttons"><button type="button" className="button" disabled={locked} onClick={()=>item.route==='SELF_CHECK'?cmd.mutate(()=>adoption(item)):setMode('adopt')}><Check size={18}/>{cmd.isPending?'수락 중…':item.route==='SELF_CHECK'?'제안 수락':item.route==='MANAGER_APPROVAL'?'수락·관리자 승인 요청':'수락·요청 발송'}</button><button type="button" className="button secondary" disabled={locked} onClick={()=>setMode('decline')}>채택 안 함</button></div>
  {cmd.error&&<ErrorMessage error={cmd.error}/>}
  {mode&&<WorkDialog item={item} mode={mode} incident={incident} onClose={()=>setMode(null)}/>}</>;
}
/** 원인 후보의 확인 항목·조치 제안. 사건 정보가 없으면 읽기 전용 문장으로만 보여 준다. */
export function CauseLines({cause,result,work}:{cause:Obj;result:Obj;work?:AnswerWork}){
 return <>{(['check','action'] as const).map(kind=>cause[kind]&&(work?<CauseProposal key={kind} incident={work.incident} canManage={work.canManage} result={result} cause={cause} kind={kind}/>:<p key={kind}>{kind==='check'?'확인 항목':'조치 제안'} · {cause[kind]}</p>))}</>;
}
/** 원인 후보의 확인 항목·조치 제안 한 줄. 아직 업무가 없으면 내용을 그대로 업무로 만들어 수락한다. */
export function CauseProposal({incident,result,cause,kind,canManage}:Answer&{cause:Obj;kind:'check'|'action'}){
 const text=String(cause[kind]||'');const q=useIncidentWork(incident.incidentId);const [draft,setDraft]=useState(false);const [review,setReview]=useState<Obj|null>(null);
 const linked=(q.data?.items||[]).find((item:Obj)=>workText(item.title)===workText(text));
 return <div className="answer-proposal"><p><strong>{kind==='check'?'확인 항목':'조치 제안'} · </strong>{text}</p>
  {linked?<ProposalActions item={linked} incident={incident} canManage={canManage}/>:<button type="button" className="button secondary" disabled={!canManage||incident.status==='CLOSED'||q.isPending} onClick={()=>setDraft(true)}><Check size={18}/>제안 수락</button>}
  {draft&&<CauseDraft incident={incident} result={result} cause={cause} kind={kind} text={text} onClose={()=>setDraft(false)} onReview={setReview}/>}
  {review&&<WorkDialog item={review} mode="adopt" incident={incident} onClose={()=>setReview(null)}/>}
 </div>;
}
function CauseDraft({incident,result,cause,kind,text,onClose,onReview}:{incident:Incident;result:Obj;cause:Obj;kind:'check'|'action';text:string;onClose:()=>void;onReview:(item:Obj)=>void}){
 const policy=usePolicy();const [operation,setOperation]=useState(kind==='check'?'REVIEW_SENSOR_DATA':'REVIEW_REQUIRED');const [title,setTitle]=useState(text);const [reason,setReason]=useState(cause.hypothesis||cause.cause||'');const cmd=useWorkCommand(onClose);
 const selectedOp=policy.data?.operations?.find((op:Obj)=>op.operationCode===operation);
 const known=new Set((result.evidenceRefs||[]).map((ref:Obj)=>ref.evidenceId));const supporting=(cause.supportingEvidence||cause.supportingEvidenceIds||[]).filter((id:string)=>known.has(id));
 const submit=()=>cmd.mutate(async()=>{
  const created=await write<Obj>(`/api/incidents/${incident.incidentId}/work-items`,{operationCode:operation,title,reason,sourceResultId:result.resultId,sourceCauseId:cause.causeId||null,evidenceIds:supporting.length?supporting:[...known],expectedIncidentVersion:incident.version});
  if(created.status!=='PROPOSED')return;
  if(created.route==='SELF_CHECK')await adoption(created);else onReview(created);
 });
 return <Modal title="제안 수락" onClose={onClose}><form onSubmit={event=>{event.preventDefault();if(!cmd.isPending)submit()}}>
  <div className="selected-action-source"><h3>{cause.hypothesis||cause.cause||'원인 후보'}</h3><p><strong>{kind==='check'?'확인 항목':'조치 제안'} · </strong>{text}</p></div>
  <Field label="업무 종류"><select value={operation} disabled={cmd.isPending} onChange={e=>setOperation(e.target.value)}>{policy.data?.operations?.map((op:Obj)=><option key={op.operationCode} value={op.operationCode}>{op.label} · {workRoutes[op.route]}</option>)}</select></Field>
  {selectedOp&&<Notice>{workRoutes[selectedOp.route]} · {selectedOp.route==='SELF_CHECK'?'수락하면 바로 내 확인 업무가 됩니다.':selectedOp.route==='MANAGER_APPROVAL'?'수락 후 지정 관리자에게 승인 요청합니다.':'수락 후 수신 담당자를 선택해 발송합니다.'}</Notice>}
  <Field label="할 일"><textarea required maxLength={2000} disabled={cmd.isPending} value={title} onChange={e=>setTitle(e.target.value)}/></Field>
  <Field label="제안 이유"><textarea required maxLength={2000} disabled={cmd.isPending} value={reason} onChange={e=>setReason(e.target.value)}/></Field>
  {policy.error&&<ErrorMessage error={policy.error}/>} {cmd.error&&<ErrorMessage error={cmd.error}/>}<button className="button full" disabled={cmd.isPending||policy.isPending||!title.trim()||!reason.trim()}>{cmd.isPending?'처리 중…':'수락'}</button>
 </form></Modal>;
}
/** Agent가 이 답변과 함께 낸 업무 제안. 원인 후보 줄과 같은 업무는 그쪽에서 보여 주므로 제외한다. */
export function AnswerSuggestions({incident,result,canManage}:Answer){
 const q=useIncidentWork(incident.incidentId);
 const causeTexts=new Set((result.candidateCauses||[]).flatMap((cause:Obj)=>[cause.check,cause.action]).filter(Boolean).map(workText));
 const items=(q.data?.items||[]).filter((item:Obj)=>item.sourceResultId===result.resultId&&item.createdBy==='agent'&&!causeTexts.has(workText(item.title)));
 if(!items.length)return null;
 return <div className="answer-suggestions"><h3>업무 제안</h3>{items.map((item:Obj)=><div className="answer-proposal" key={item.taskId}><span className={'work-route '+item.route.toLowerCase()}>{workRoutes[item.route]}</span><p><strong>{item.title}</strong></p><p className="small muted">{item.reason}</p><ProposalActions item={item} incident={incident} canManage={canManage}/></div>)}</div>;
}
