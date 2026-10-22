import {useState} from 'react';
import {useMutation,useQueries,useQuery,useQueryClient} from '@tanstack/react-query';
import {Plus} from 'lucide-react';
import {api,write} from '../api/client';
import {type Incident,type Obj,type Page,labels,when} from '../api/types';
import {actionKinds,buildActionChoices,actionSourceKey,type ActionChoice} from '../domain/actionSources';
import {WorkItemsPanel} from './WorkItems';
import {Badge,Empty,ErrorMessage,Field,Modal,Panel} from './ui';

async function fetchAllProposals(incidentId:string):Promise<Obj[]>{
 const items:Obj[]=[];
 let cursor:string|null=null;
 do{
  const cursorParam=cursor?'&cursor='+encodeURIComponent(cursor):'';
  const page:Page<Obj>=await api(`/api/approvals?incidentId=${incidentId}&size=100${cursorParam}`);
  items.push(...page.items);
  cursor=page.nextCursor;
 }while(cursor);
 return items;
}
/** 조치 기록에 연결할 수 있는 분석 결과 · 승인 제안 — 지난 분석과 제안 상세까지 불러온다 */
function useActionSources(incident:Incident,analysis:Obj|undefined,actions:Obj[]){
 const historyResultIds=[...new Set<string>((analysis?.history||[]).map((result:Obj)=>result.resultId))]
  .filter(id=>id!==analysis?.resultId);
 const histories=useQueries({queries:historyResultIds.map(id=>({
  queryKey:['analysis-source',incident.incidentId,id],
  queryFn:()=>api<Obj>(`/api/incidents/${incident.incidentId}/analysis-results?resultId=${encodeURIComponent(id)}`),
 }))});
 const proposalList=useQuery({
  queryKey:['action-proposals',incident.incidentId],
  queryFn:()=>fetchAllProposals(incident.incidentId),
  refetchInterval:5000,
 });
 const proposalDetails=useQueries({queries:(proposalList.data||[]).map(proposal=>({
  queryKey:['approval',proposal.approvalId],
  queryFn:()=>api<Obj>('/api/approvals/'+proposal.approvalId),
 }))});
 const loadedHistories=histories.flatMap(query=>query.data?[query.data]:[]);
 const loadedProposals=proposalDetails.flatMap(query=>query.data?[query.data]:[]);
 return {
  analysisSources:buildActionChoices([...(analysis?[analysis]:[]),...loadedHistories],loadedProposals,actions),
  sourceResults:[...(analysis?[analysis]:[]),...loadedHistories],
  sources:buildActionChoices([],[],actions).filter(source=>source.type!=='WORK_ITEM'),
  sourceError:proposalList.error||histories.find(query=>query.error)?.error||proposalDetails.find(query=>query.error)?.error,
 };
}
function ActionRecordCard({action,linkLabel,canManage,onVerify}:{action:Obj;linkLabel:string;canManage:boolean;onVerify:()=>void}){
 return <div className="record">
  <div className="row-between">
   <h3>{actionKinds[action.actionType]||action.actionType}</h3>
   <Badge value={action.verificationStatus==='PENDING'?'NOT_VERIFIED':action.verificationStatus}/>
  </div>
  <p className="small action-link-label">{linkLabel}</p>
  <p>{action.description}</p>
  <p className="small muted">{when(action.performedAt)} KST · {action.performedBy}</p>
  {action.verifications?.map((verification:Obj)=><div className="verification-record" key={verification.verificationId}>
   <Badge value={verification.outcome}/>
   <p>{verification.rationale}</p>
   <span className="small muted">{when(verification.verifiedAt)} KST · {verification.verifiedBy}</span>
  </div>)}
  <button className="button secondary" disabled={!canManage} onClick={onVerify}>확인·효과 기록 추가</button>
 </div>;
}
function SelectedSourceSummary({choice}:{choice:ActionChoice}){
 return <div className="selected-action-source">
  <h3>{choice.title}</h3>
  {choice.rationale&&<p>{choice.rationale}</p>}
  {choice.check&&<p><strong>확인 제안 · </strong>{choice.check}</p>}
  {choice.action&&<p><strong>조치 제안 · </strong>{choice.action}</p>}
 </div>;
}
function actionRecordBody(incident:Incident,form:{kind:string;description:string;performed:string;confirmed:boolean},choice?:ActionChoice){
 return {
  actionType:form.kind,
  description:form.description,
  performedAt:new Date(form.performed+'+09:00').toISOString(),
  confirmedByUser:form.confirmed,
  expectedIncidentVersion:incident.version,
  evidenceIds:[],
  relatedSource:choice?{type:choice.type,refId:choice.refId,...(choice.itemId!=null?{itemId:choice.itemId}:{})}:null,
 };
}
export function ActionWorkspace({canManage,incident,analysis,actions,actionsError,onVerify}:{
 canManage:boolean;
 incident:Incident;
 analysis?:Obj;
 actions:Obj[];
 actionsError?:unknown;
 onVerify:(action:Obj)=>void;
}){
 const [selection,setSelection]=useState<string|null>(null);
 const {analysisSources,sourceResults,sources,sourceError}=useActionSources(incident,analysis,actions);
 const linkLabel=(action:Obj)=>actionSourceKey(action)
  ?'연결 항목 · '+(action.relatedSource?.title||sources.find(source=>source.key===actionSourceKey(action))?.title||'승인된 제안')
  :'별도 기록';
 const addRecordButton=<button className="button secondary" disabled={!canManage||incident.status==='CLOSED'} onClick={()=>setSelection('')}>
  <Plus size={18}/>별도 기록 추가
 </button>;
 return <div className="side-stack">
  {sourceError&&<ErrorMessage error={sourceError}/>}
  <WorkItemsPanel incident={incident} results={sourceResults} canManage={canManage}
   sources={analysisSources.filter(source=>source.type==='CAUSE'||source.type==='NEXT_ACTION')}/>
  <Panel title="작성한 검토·점검·조치 기록" extra={addRecordButton}>
   {actionsError?<ErrorMessage error={actionsError}/>:actions.length?actions.map(action=><ActionRecordCard
    key={action.actionId}
    action={action}
    linkLabel={linkLabel(action)}
    canManage={canManage}
    onVerify={()=>onVerify(action)}
   />):<Empty>별도로 작성한 조치 기록이 없습니다.</Empty>}
  </Panel>
  {selection!==null&&<ActionRecordModal incident={incident} sources={sources} initialSelection={selection} onClose={()=>setSelection(null)}/>}
 </div>;
}
type RecordForm={kind:string;description:string;performed:string;confirmed:boolean};
function ActionRecordFields({busy,form,onChange}:{
 busy:boolean;
 form:RecordForm;
 onChange:{kind:(kind:string)=>void;description:(description:string)=>void;performed:(performed:string)=>void;confirmed:(confirmed:boolean)=>void};
}){
 return <>
  <Field label="기록 종류">
   <select required value={form.kind} disabled={busy} onChange={event=>onChange.kind(event.target.value)}>
    <option value="">기록 종류 선택</option>
    {Object.entries(actionKinds).map(([value,label])=><option key={value} value={value}>{label}</option>)}
   </select>
  </Field>
  <Field label="실제로 검토·확인·수행한 내용">
   <textarea
    required
    maxLength={4000}
    value={form.description}
    disabled={busy}
    onChange={event=>onChange.description(event.target.value)}
    placeholder="확인한 대상, 수행한 작업, 관측한 결과를 기록하세요."
   />
  </Field>
  <Field label="수행 시각 (KST)">
   <input required type="datetime-local" value={form.performed} disabled={busy} onChange={event=>onChange.performed(event.target.value)}/>
  </Field>
  <label className="check">
   <input type="checkbox" required checked={form.confirmed} disabled={busy} onChange={event=>onChange.confirmed(event.target.checked)}/>실제로 검토·확인·수행한 내용입니다.
  </label>
 </>;
}
function ActionRecordModal({incident,sources,initialSelection,onClose}:{
 incident:Incident;sources:ActionChoice[];initialSelection:string;onClose:()=>void;
}){
 const queryClient=useQueryClient();
 const [selected,setSelected]=useState(initialSelection);
 const [kind,setKind]=useState('');
 const [description,setDescription]=useState('');
 const [confirmed,setConfirmed]=useState(false);
 const [performed,setPerformed]=useState(new Date(Date.now()+9*3600000-60000).toISOString().slice(0,16));
 const choice=sources.find(source=>source.key===selected);
 const mutation=useMutation({
  mutationFn:()=>write(`/api/incidents/${incident.incidentId}/actions`,actionRecordBody(incident,{kind,description,performed,confirmed},choice)),
  onSuccess:async()=>{
   await Promise.all(['actions','incident','reports'].map(key=>queryClient.invalidateQueries({queryKey:[key,incident.incidentId]})));
   onClose();
  },
  onError:()=>queryClient.invalidateQueries({queryKey:['incident',incident.incidentId]}),
 });
 const busy=mutation.isPending;
 return <Modal title="검토·점검·조치 기록" onClose={onClose}>
  <form onSubmit={event=>{event.preventDefault();if(!mutation.isPending)mutation.mutate()}}>
   <Field label="관련 분석·제안">
    <select value={selected} disabled={busy} onChange={event=>setSelected(event.target.value)}>
     <option value="">연결 없이 별도 기록</option>
     {sources.map(source=><option key={source.key} value={source.key}>{source.subtitle} · {source.title}</option>)}
    </select>
   </Field>
   {choice&&<SelectedSourceSummary choice={choice}/>}
   <ActionRecordFields
    busy={busy}
    form={{kind,description,performed,confirmed}}
    onChange={{kind:setKind,description:setDescription,performed:setPerformed,confirmed:setConfirmed}}
   />
   {mutation.error&&<ErrorMessage error={mutation.error}/>}
   <button className="button full" disabled={busy||!kind||!description.trim()||!confirmed}>{busy?'저장 중…':'기록 저장'}</button>
  </form>
 </Modal>;
}
