import {useState} from 'react';
import {useMutation,useQueries,useQuery,useQueryClient} from '@tanstack/react-query';
import {Plus,ArrowUpRight} from 'lucide-react';
import {api,write} from '../api/client';
import {type Incident,type Obj,type Page,labels,when} from '../api/types';
import {actionKinds,buildActionChoices,actionSourceKey,type ActionChoice} from '../domain/actionSources';
import {Badge,Empty,ErrorMessage,Field,Modal,Panel} from './ui';

export function ActionWorkspace({incident,analysis,actions,actionsError,onVerify}:{incident:Incident;analysis?:Obj;actions:Obj[];actionsError?:unknown;onVerify:(action:Obj)=>void}){
 const [selection,setSelection]=useState<string|null>(null);
 const histories=useQueries({queries:[...new Set<string>((analysis?.history||[]).map((result:Obj)=>result.resultId))].filter(id=>id!==analysis?.resultId).map(id=>({queryKey:['analysis-source',incident.incidentId,id],queryFn:()=>api<Obj>(`/api/incidents/${incident.incidentId}/analysis-results?resultId=${encodeURIComponent(id)}`)}))});
 const proposalList=useQuery({queryKey:['action-proposals',incident.incidentId],queryFn:async()=>{
  const items:Obj[]=[];let cursor:string|null=null;
  do{const page:Page<Obj>=await api(`/api/approvals?incidentId=${incident.incidentId}&size=100${cursor?'&cursor='+encodeURIComponent(cursor):''}`);items.push(...page.items);cursor=page.nextCursor}while(cursor);
  return items;
 },refetchInterval:5000});
 const proposalDetails=useQueries({queries:(proposalList.data||[]).map(proposal=>({queryKey:['approval',proposal.approvalId],queryFn:()=>api<Obj>('/api/approvals/'+proposal.approvalId)}))});
 const sources=buildActionChoices([...(analysis?[analysis]:[]),...histories.flatMap(query=>query.data?[query.data]:[])],proposalDetails.flatMap(query=>query.data?[query.data]:[]),actions);
 const loading=proposalList.isPending||histories.some(query=>query.isPending)||proposalDetails.some(query=>query.isPending);
 const sourceError=proposalList.error||histories.find(query=>query.error)?.error||proposalDetails.find(query=>query.error)?.error;
 return <div className="side-stack">
  <Panel title="분석·제안별 확인 및 조치" extra={<button className="button" disabled={incident.status==='CLOSED'} onClick={()=>setSelection('')}><Plus size={18}/>별도 기록 추가</button>}>
   {sourceError&&<ErrorMessage error={sourceError}/>}
   <div className="action-source-grid">{sources.map(source=>{
    const records=actions.filter(action=>actionSourceKey(action)===source.key);
    const reviews=records.filter(record=>record.actionType==='REVIEW').length;
    const inspections=records.filter(record=>record.actionType==='INSPECTION').length;
    const performed=records.length-reviews-inspections;
    return <button className="action-source-card" key={source.key} disabled={incident.status==='CLOSED'} onClick={()=>setSelection(source.key)}>
     <span className="eyebrow">{source.subtitle}</span><h3>{source.title}</h3>
     {source.check&&<p className="small muted">확인 항목 · {source.check}</p>}
     <div className="row-between"><span className="small">{records.length?`검토 ${reviews} · 점검 ${inspections} · 조치/기타 ${performed}`:'아직 기록 없음'}</span><span className="text-button">기록 추가 <ArrowUpRight size={16}/></span></div>
    </button>;
   })}</div>
   {!sources.length&&(loading?<Empty>분석·제안 항목을 불러오는 중…</Empty>:<Empty>연결할 분석·제안이 없습니다. 별도 기록을 추가할 수 있습니다.</Empty>)}
  </Panel>
  <Panel title="작성한 검토·점검·조치 기록">
   {actionsError?<ErrorMessage error={actionsError}/>:actions.length?actions.map(action=><div className="record" key={action.actionId}>
    <div className="row-between"><h3>{actionKinds[action.actionType]||action.actionType}</h3><Badge value={action.verificationStatus==='PENDING'?'NOT_VERIFIED':action.verificationStatus}/></div>
    <p className="small action-link-label">{actionSourceKey(action)?'연결 항목 · '+(action.relatedSource?.title||sources.find(source=>source.key===actionSourceKey(action))?.title||'승인된 제안'):'별도 기록'}</p>
    <p>{action.description}</p><p className="small muted">{when(action.performedAt)} KST · {action.performedBy}</p>
    {action.verifications?.map((verification:Obj)=><div className="verification-record" key={verification.verificationId}><Badge value={verification.outcome}/><p>{verification.rationale}</p><span className="small muted">{when(verification.verifiedAt)} KST · {verification.verifiedBy}</span></div>)}
    <button className="button secondary" onClick={()=>onVerify(action)}>확인·효과 기록 추가</button>
   </div>):<Empty>아직 검토·점검·조치 기록이 없습니다.</Empty>}
  </Panel>
  {selection!==null&&<ActionRecordModal incident={incident} sources={sources} initialSelection={selection} onClose={()=>setSelection(null)}/>}
 </div>;
}
function ActionRecordModal({incident,sources,initialSelection,onClose}:{incident:Incident;sources:ActionChoice[];initialSelection:string;onClose:()=>void}){
 const qc=useQueryClient();const [selected,setSelected]=useState(initialSelection);const [kind,setKind]=useState('');const [description,setDescription]=useState('');const [confirmed,setConfirmed]=useState(false);
 const [performed,setPerformed]=useState(new Date(Date.now()+9*3600000-60000).toISOString().slice(0,16));
 const choice=sources.find(source=>source.key===selected);
 const mutation=useMutation({mutationFn:()=>write(`/api/incidents/${incident.incidentId}/actions`,{
  actionType:kind,description,performedAt:new Date(performed+'+09:00').toISOString(),confirmedByUser:confirmed,expectedIncidentVersion:incident.version,evidenceIds:[],relatedSource:choice?{type:choice.type,refId:choice.refId,...(choice.itemId!=null?{itemId:choice.itemId}:{})}:null,
 }),onSuccess:async()=>{await Promise.all(['actions','incident','reports'].map(key=>qc.invalidateQueries({queryKey:[key,incident.incidentId]})));onClose()},onError:()=>qc.invalidateQueries({queryKey:['incident',incident.incidentId]})});
 return <Modal title="검토·점검·조치 기록" onClose={onClose}><form onSubmit={event=>{event.preventDefault();if(!mutation.isPending)mutation.mutate()}}>
  <Field label="관련 분석·제안"><select value={selected} disabled={mutation.isPending} onChange={event=>setSelected(event.target.value)}><option value="">연결 없이 별도 기록</option>{sources.map(source=><option key={source.key} value={source.key}>{source.subtitle} · {source.title}</option>)}</select></Field>
  {choice&&<div className="selected-action-source"><h3>{choice.title}</h3>{choice.rationale&&<p>{choice.rationale}</p>}{choice.check&&<p><strong>확인 제안 · </strong>{choice.check}</p>}{choice.action&&<p><strong>조치 제안 · </strong>{choice.action}</p>}</div>}
  <Field label="기록 종류"><select required value={kind} disabled={mutation.isPending} onChange={event=>setKind(event.target.value)}><option value="">기록 종류 선택</option>{Object.entries(actionKinds).map(([value,label])=><option key={value} value={value}>{label}</option>)}</select></Field>
  <Field label="실제로 검토·확인·수행한 내용"><textarea required maxLength={4000} value={description} disabled={mutation.isPending} onChange={event=>setDescription(event.target.value)} placeholder="확인한 대상, 수행한 작업, 관측한 결과를 기록하세요."/></Field>
  <Field label="수행 시각 (KST)"><input required type="datetime-local" value={performed} disabled={mutation.isPending} onChange={event=>setPerformed(event.target.value)}/></Field>
  <label className="check"><input type="checkbox" required checked={confirmed} disabled={mutation.isPending} onChange={event=>setConfirmed(event.target.checked)}/>실제로 검토·확인·수행한 내용입니다.</label>
  {mutation.error&&<ErrorMessage error={mutation.error}/>}
  <button className="button full" disabled={mutation.isPending||!kind||!description.trim()||!confirmed}>{mutation.isPending?'저장 중…':'기록 저장'}</button>
 </form></Modal>;
}
