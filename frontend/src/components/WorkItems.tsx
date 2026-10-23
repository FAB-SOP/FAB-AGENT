import {useState} from 'react';
import {Link} from 'react-router-dom';
import {useMutation,useQuery,useQueryClient} from '@tanstack/react-query';
import {Plus,CheckCircle2} from 'lucide-react';
import {api,write} from '../api/client';
import {type Incident,type Obj,when} from '../api/types';
import {type ActionChoice} from '../domain/actionSources';
import {canManageInvestigation} from '../domain/permissions';
import {useAuth} from '../auth';
import {Badge,Empty,ErrorMessage,Field,Modal,Notice,Panel,PageHeading} from './ui';

export const workRoutes:Record<string,string>={
 SELF_CHECK:'내 확인 업무',
 MANAGER_APPROVAL:'관리자 승인 필요',
 TEAM_REQUEST:'다른 팀 요청',
};
export const workStates:Record<string,string>={
 PROPOSED:'채택 대기',
 DECLINED:'미채택',
 READY:'확인 진행 가능',
 WAITING_MANAGER:'관리자 승인 대기',
 APPROVED:'관리자 승인 완료',
 REJECTED:'관리자 반려',
 REQUESTED:'담당자 회신 대기',
 COMPLETED:'결과 기록 완료',
 CLOSED_UNPERFORMED:'미수행 종결',
};
/** 채택했지만 수행하지 않은 업무 — 사유를 적어 「미수행으로 닫기」할 수 있다(보고서 확정 전 정리) */
const UNPERFORMED_CLOSABLE=['READY','APPROVED','REQUESTED'];
const PROCESS_ORDER=['CVD','ETCH','CMP'];
const usePolicy=()=>useQuery({queryKey:['work-policy'],queryFn:()=>api<Obj>('/api/work-policy'),staleTime:60000});
const requestLink=(requestId:string)=>'/check-requests?request='+encodeURIComponent(requestId);
const nowInKstInput=()=>new Date(Date.now()+9*3600000-60000).toISOString().slice(0,16);
function useWorkCommand(onSaved:()=>void){
 const queryClient=useQueryClient();
 return useMutation({
  mutationFn:(operation:()=>Promise<unknown>)=>operation(),
  onSuccess:async()=>{
   await queryClient.invalidateQueries({predicate:query=>query.queryKey[0]!=='session'});
   onSaved();
  },
  onError:()=>queryClient.invalidateQueries({
   predicate:query=>['incident','work-items','my-work','work-approvals'].includes(String(query.queryKey[0])),
  }),
 });
}
/** 같은 업무 종류 · 같은 제목(문장부호 · 공백 무시)은 하나의 제안으로 본다 */
const workKey=(item:Obj)=>
 item.operationCode+':'+String(item.title).normalize('NFKC').toLowerCase().replace(/[\p{P}\p{Z}\s]/gu,'');
/** 채택 대기(같은 업무가 이미 진행 중이면 숨김) · 채택한 내 업무 · 미채택/반려로 나눈다 */
function classifyWorkItems(items:Obj[]){
 const proposed=items
  .filter(item=>item.status==='PROPOSED')
  .filter((item,index,list)=>list.findIndex(other=>workKey(other)===workKey(item))===index
   &&!items.some(other=>other.status!=='PROPOSED'&&workKey(other)===workKey(item)));
 const adopted=items.filter(item=>item.route!=='TEAM_REQUEST'&&!['PROPOSED','DECLINED','REJECTED','CLOSED_UNPERFORMED'].includes(item.status));
 const archived=items.filter(item=>['DECLINED','REJECTED','CLOSED_UNPERFORMED'].includes(item.status));
 return {proposed,adopted,archived};
}
function useIncidentWork(incidentId:string){
 const workItems=useQuery({
  queryKey:['work-items',incidentId],
  queryFn:()=>api<Obj>(`/api/incidents/${incidentId}/work-items`),
  refetchInterval:3000,
 });
 const requests=useQuery({
  queryKey:['incident-requests',incidentId],
  queryFn:()=>api<Obj>(`/api/incidents/${incidentId}/check-requests`),
  refetchInterval:3000,
 });
 return {workItems,requests};
}
/** 승인만 된 변경은 수행 전이므로 '실제 수행' 기록임을 분명히 한다 */
const completeLabel=(item:Obj)=>item.status==='APPROVED'?'실제 수행 결과 기록':'수행·확인 결과 기록';
const adoptLabel=(item:Obj)=>item.route==='SELF_CHECK'
 ?'내 확인 목록에 추가'
 :item.route==='MANAGER_APPROVAL'?'채택하고 관리자 승인 요청':'요청 내용 검토·발송';
function ProposedWorkCard({item,disabled,onSelect}:{item:Obj;disabled:boolean;onSelect:(mode:string)=>void}){
 return <WorkCard item={item}>
  <div className="work-buttons">
   <button className="button" disabled={disabled} onClick={()=>onSelect('adopt')}>{adoptLabel(item)}</button>
   <button className="button secondary" disabled={disabled} onClick={()=>onSelect('decline')}>미채택</button>
  </div>
 </WorkCard>;
}
function AdoptedWorkCard({item,canComplete,canClose,closed,onComplete,onCloseUnperformed}:{
 item:Obj;canComplete:boolean;canClose:boolean;closed:boolean;onComplete:()=>void;onCloseUnperformed:()=>void;
}){
 return <WorkCard item={item}>
  {item.approverUserId&&<p className="small muted">승인 담당 · {item.approverUserId}</p>}
  {item.status==='WAITING_MANAGER'&&<p className="work-waiting">관리자가 검토하고 있습니다. 승인 후 수행 결과를 기록할 수 있습니다.</p>}
  {canComplete&&<button className="button" disabled={closed} onClick={onComplete}><CheckCircle2 size={18}/>{completeLabel(item)}</button>}
  {canClose&&<button className="button secondary" disabled={closed} onClick={onCloseUnperformed}>미수행으로 닫기</button>}
  {item.requestId&&<Link className="text-button" to={requestLink(item.requestId)}>요청·회신 확인</Link>}
  {item.actionId&&<p className="small muted">수행 기록 · {item.actionId}</p>}
 </WorkCard>;
}
export function WorkItemsPanel({incident,sources,results,canManage}:{
 incident:Incident;sources:ActionChoice[];results:Obj[];canManage:boolean;
}){
 const {session}=useAuth();
 const {workItems,requests}=useIncidentWork(incident.incidentId);
 const [draft,setDraft]=useState(false);
 const [selected,setSelected]=useState<{item:Obj;mode:string}|null>(null);
 const {proposed,adopted,archived}=classifyWorkItems(workItems.data?.items||[]);
 const closed=incident.status==='CLOSED';
 const proposeButton=<button className="button secondary" disabled={!canManage||closed||!sources.length} onClick={()=>setDraft(true)}>
  <Plus size={18}/>분석에서 업무 제안
 </button>;
 const canComplete=(item:Obj)=>['READY','APPROVED'].includes(item.status)&&item.assigneeUserId===session?.user.userId&&canManage;
 const canClose=(item:Obj)=>UNPERFORMED_CLOSABLE.includes(item.status)&&(canManage||item.assigneeUserId===session?.user.userId);
 return <><Panel title="확인·조치 제안" extra={proposeButton}>
  {workItems.error?<ErrorMessage error={workItems.error}/>:proposed.length?proposed.map(item=><ProposedWorkCard
   key={item.taskId} item={item} disabled={!canManage||closed} onSelect={mode=>setSelected({item,mode})}
  />):<Empty>
   {workItems.isPending?'업무 제안을 불러오는 중…':'채택 대기 중인 제안이 없습니다. 분석 근거에서 업무를 제안할 수 있습니다.'}
  </Empty>}
 </Panel><Panel title="분석·제안별 확인 및 조치">
  {adopted.length?adopted.map(item=><AdoptedWorkCard
   key={item.taskId} item={item} canComplete={canComplete(item)} canClose={canClose(item)} closed={closed}
   onComplete={()=>setSelected({item,mode:'complete'})} onCloseUnperformed={()=>setSelected({item,mode:'unperformed'})}
  />):!requests.data?.items?.length&&<Empty>채택한 업무가 없습니다. 제안을 검토해 확인 목록에 추가하세요.</Empty>}
  {requests.error&&<ErrorMessage error={requests.error}/>}
  {(requests.data?.items||[]).map((request:Obj)=><TeamRequestResult key={request.requestId} request={request}/>)}
 </Panel>{archived.length>0&&<details className="work-archive">
  <summary>미채택·반려·미수행 종결 {archived.length}건</summary>
  {archived.map(item=><WorkCard key={item.taskId} item={item}/>)}
 </details>}
 {draft&&<WorkDraft incident={incident} sources={sources} results={results} onClose={()=>setDraft(false)}/>}
 {selected&&<WorkDialog item={selected.item} mode={selected.mode} incident={incident} onClose={()=>setSelected(null)}/>}
 </>;
}
/** 분석 결과별로 연결된 제안(sourceResultId)을 상태와 관계없이 모두 보여주고, 상태에 따라 모양만 바꾼다 */
export function ProposedWorkList({incident,canManage,results}:{incident:Incident;canManage:boolean;results:Obj[]}){
 const {workItems}=useIncidentWork(incident.incidentId);
 const [selected,setSelected]=useState<{item:Obj;mode:string}|null>(null);
 const groups=groupBySourceResult(workItems.data?.items||[],results);
 if(!groups.length)return null;
 const disabled=!canManage||incident.status==='CLOSED';
 const waiting=groups.reduce((count,group)=>count+group.items.filter((item:Obj)=>item.status==='PROPOSED').length,0);
 return <section className="proposed-work">
  <h3>추천 조치안 <span className="small muted">채택 대기 {waiting}건 · 진행할 조치를 선택하세요</span></h3>
  {groups.map(group=><div key={group.resultId} className="proposed-group">
   <p className="small muted">{group.label}</p>
   {group.items.map((item:Obj)=>item.status==='PROPOSED'
    ?<ProposedWorkCard key={item.taskId} item={item} disabled={disabled} onSelect={mode=>setSelected({item,mode})}/>
    :<WorkCard key={item.taskId} item={item} muted={['DECLINED','REJECTED'].includes(item.status)}/>)}
  </div>)}
  {selected&&<WorkDialog item={selected.item} mode={selected.mode} incident={incident} onClose={()=>setSelected(null)}/>}
 </section>;
}
/** 제안을 근거 분석 결과별로 묶는다 — 최신 분석이 위, 화면에 없는 이전 분석은 '이전 분석'으로 */
export function groupBySourceResult(items:Obj[],results:Obj[]){
 const byResult=new Map<string,Obj[]>();
 items.filter(item=>item.sourceResultId).forEach(item=>byResult.set(item.sourceResultId,[...(byResult.get(item.sourceResultId)||[]),item]));
 return [...byResult.entries()].map(([resultId,grouped])=>{
  const result=results.find(candidate=>candidate.resultId===resultId);
  const label=result?(result.kind==='DEEP'?'추가 질문 답변':'기본 분석')+' · '+when(result.createdAt)+' KST':'이전 분석';
  return {resultId,label,at:result?Date.parse(result.createdAt):0,items:grouped};
 }).sort((first,second)=>second.at-first.at);
}
function TeamRequestResult({request}:{request:Obj}) {
 const replies:Obj[]=request.replies||[];
 const replied=request.status==='REPLIED'&&replies.length>0;
 return <article className="work-card team-result">
  <div className="work-card-header">
   <span className="work-route team_request">다른 팀 요청 · {request.targetProcessCode}</span>
   <Badge value={replied?'COMPLETED':'REQUESTED'}>{replied?'담당자 확인·회신 완료':'담당자 회신 대기'}</Badge>
  </div>
  <h3>{request.question}</h3>
  <p className="small muted">{request.equipmentId} · 담당 {request.ownerUserId} · 요청 {when(request.createdAt)} KST</p>
  {replies.map(reply=><div className="team-reply" key={reply.replyId}>
   <strong>담당자 확인·조치 결과</strong>
   <p>{reply.findings}</p>
   <span className="small muted">{when(reply.createdAt)} KST · {reply.authorUserId}</span>
  </div>)}
  {replied&&<p className="small muted">요청 회신 완료 · 사건의 해결 효과는 별도 확인</p>}
  <Link className="text-button" to={requestLink(request.requestId)}>요청·회신 상세 보기</Link>
 </article>;
}
function WorkCard({item,children,muted=false}:{item:Obj;children?:React.ReactNode;muted?:boolean}){
 return <article className={'work-card'+(muted?' muted':'')}>
  <div className="work-card-header">
   <span className={'work-route '+item.route.toLowerCase()}>{workRoutes[item.route]}</span>
   <Badge value={item.status}>{workStates[item.status]}</Badge>
  </div>
  <h3>{item.title}</h3>
  <p>{item.reason}</p>
  <div className="work-meta">
   <span>{item.operationLabel}</span>
   <span>{when(item.createdAt)} KST</span>
  </div>
  {item.decisionComment&&<p className="small">검토 의견 · {item.decisionComment}</p>}
  {item.closeReason&&<p className="small">미수행 사유 · {item.closeReason} ({item.closedBy})</p>}
  {item.status==='APPROVED'&&<p className="work-not-done">승인됨 · 아직 수행하지 않았습니다. 실제로 수행한 뒤 결과를 기록하세요.</p>}
  {children}
 </article>;
}
function routeNotice(route:string){
 if(route==='SELF_CHECK')return '채택하면 내 확인 업무가 됩니다.';
 return route==='MANAGER_APPROVAL'?'채택 후 지정 관리자에게 승인 요청합니다.':'채택 시 수신 담당자를 확인하고 발송합니다.';
}
function proposeWorkItem(incident:Incident,source:ActionChoice,result:Obj,draft:{operation:string;title:string;reason:string}){
 return write(`/api/incidents/${incident.incidentId}/work-items`,{
  operationCode:draft.operation,
  title:draft.title,
  reason:draft.reason,
  sourceResultId:source.refId,
  sourceCauseId:source.type==='CAUSE'?source.itemId:null,
  evidenceIds:(result.evidenceRefs||[]).map((ref:Obj)=>ref.evidenceId),
  expectedIncidentVersion:incident.version,
 });
}
function OperationField({operations,operation,disabled,onChange}:{
 operations?:Obj[];operation:string;disabled:boolean;onChange:(operation:string)=>void;
}){
 const selectedOperation=operations?.find((option:Obj)=>option.operationCode===operation);
 return <>
  <Field label="업무 종류">
   <select value={operation} disabled={disabled} onChange={event=>onChange(event.target.value)}>
    {operations?.map((option:Obj)=><option key={option.operationCode} value={option.operationCode}>
     {option.label} · {workRoutes[option.route]}
    </option>)}
   </select>
  </Field>
  {selectedOperation&&<Notice>{workRoutes[selectedOperation.route]} · {routeNotice(selectedOperation.route)}</Notice>}
 </>;
}
function WorkDraft({incident,sources,results,onClose}:{
 incident:Incident;sources:ActionChoice[];results:Obj[];onClose:()=>void;
}){
 const policy=usePolicy();
 const [sourceKey,setSourceKey]=useState(sources[0]?.key||'');
 const [operation,setOperation]=useState('REVIEW_SENSOR_DATA');
 const [title,setTitle]=useState('센서 실측값과 설정값·계측 결과 비교 확인');
 const [reason,setReason]=useState(sources[0]?.title||'');
 const command=useWorkCommand(onClose);
 const source=sources.find(choice=>choice.key===sourceKey);
 const result=results.find(item=>item.resultId===source?.refId);
 const submit=()=>{
  if(!source||!result||command.isPending)return;
  command.mutate(()=>proposeWorkItem(incident,source,result,{operation,title,reason}));
 };
 const chooseSource=(key:string)=>{
  setSourceKey(key);
  setReason(sources.find(choice=>choice.key===key)?.title||'');
 };
 return <Modal title="분석에서 확인·조치 업무 제안" onClose={onClose}>
  <form onSubmit={event=>{event.preventDefault();submit()}}>
   <Field label="분석 근거">
    <select required value={sourceKey} disabled={command.isPending} onChange={event=>chooseSource(event.target.value)}>
     {sources.map(choice=><option key={choice.key} value={choice.key}>{choice.subtitle} · {choice.title}</option>)}
    </select>
   </Field>
   <OperationField operations={policy.data?.operations} operation={operation} disabled={command.isPending} onChange={setOperation}/>
   <Field label="할 일">
    <textarea required maxLength={2000} disabled={command.isPending} value={title} onChange={event=>setTitle(event.target.value)}/>
   </Field>
   <Field label="제안 이유">
    <textarea required maxLength={2000} disabled={command.isPending} value={reason} onChange={event=>setReason(event.target.value)}/>
   </Field>
   {policy.error&&<ErrorMessage error={policy.error}/>} {command.error&&<ErrorMessage error={command.error}/>}
   <button className="button full" disabled={command.isPending||policy.isPending||!source||!result}>제안 저장</button>
  </form>
 </Modal>;
}
const dialogTitles=(item:Obj,isTeam:boolean):Obj=>({
 adopt:item.route==='MANAGER_APPROVAL'?' 채택·관리자 승인 요청':isTeam?'다른 팀 요청 발송':'내 확인 목록에 추가',
 decline:'제안 미채택',
 approve:'변경 계획 승인',
 reject:'변경 계획 반려',
 complete:'수행·확인 결과 기록',
 unperformed:'미수행으로 닫기',
});
const decisionCodes:Obj={decline:'DECLINE',approve:'APPROVE',reject:'REJECT'};
/** 관리자 반려 · 업무 미수행 종결은 사유 필수. AI 제안 미채택 사유는 선택 */
export const reasonRequired=(mode:string)=>mode==='reject'||mode==='unperformed';
function useWorkDialogForm(item:Obj){
 const [comment,setComment]=useState('');
 const [description,setDescription]=useState('');
 const [confirmed,setConfirmed]=useState(false);
 const [target,setTarget]=useState(item.operationCode==='UPSTREAM_CHECK'?(item.processCode==='CMP'?'ETCH':'CVD'):item.processCode);
 const [owner,setOwner]=useState('');
 const [performed,setPerformed]=useState(nowInKstInput());
 return {comment,setComment,description,setDescription,confirmed,setConfirmed,target,setTarget,owner,setOwner,performed,setPerformed};
}
type WorkDialogForm=ReturnType<typeof useWorkDialogForm>;
/** 모드별 저장 요청 — 수행 기록은 사건 버전을 알아야 보낼 수 있다 */
function workDialogRequest(item:Obj,mode:string,form:WorkDialogForm,targetIncident?:Incident){
 if(mode==='adopt'){
  return ()=>write(`/api/work-items/${item.taskId}/adoptions`,{
   expectedVersion:item.version,ownerUserId:form.owner,targetProcessCode:form.target,
  });
 }
 if(mode==='unperformed'){
  return ()=>write(`/api/work-items/${item.taskId}/unperformed-closures`,{reason:form.comment.trim(),expectedVersion:item.version});
 }
 if(mode==='complete'){
  if(!targetIncident)return null;
  return ()=>write(`/api/work-items/${item.taskId}/completions`,{
   description:form.description,
   performedAt:new Date(form.performed+'+09:00').toISOString(),
   confirmedByUser:form.confirmed,
   expectedVersion:item.version,
   expectedIncidentVersion:targetIncident.version,
  });
 }
 return ()=>write(`/api/work-items/${item.taskId}/decisions`,{
  decision:decisionCodes[mode],comment:form.comment,expectedVersion:item.version,
 });
}
function isDialogSubmitDisabled(mode:string,isTeam:boolean,busy:boolean,form:WorkDialogForm,targetIncident?:Incident){
 return busy
  ||isTeam&&!form.owner
  ||mode==='complete'&&(!targetIncident||!form.confirmed||!form.description.trim())
  ||reasonRequired(mode)&&!form.comment.trim();
}
function TeamRecipientFields({item,form,directory,busy}:{item:Obj;form:WorkDialogForm;directory:Obj[];busy:boolean}){
 const isUpstreamCheck=item.operationCode==='UPSTREAM_CHECK';
 const recipients=directory.filter((user:Obj)=>
  user.kind===(isUpstreamCheck?'PROCESS_OWNER':'MAINTENANCE')&&user.processScopes.includes(form.target));
 const targetOptions=PROCESS_ORDER.filter(code=>!isUpstreamCheck||PROCESS_ORDER.indexOf(code)<PROCESS_ORDER.indexOf(item.processCode));
 return <>
  <Field label="요청 대상 공정">
   <select value={form.target} disabled={busy||!isUpstreamCheck} onChange={event=>{form.setTarget(event.target.value);form.setOwner('')}}>
    {targetOptions.map(code=><option key={code}>{code}</option>)}
   </select>
  </Field>
  <Field label="수신 담당자">
   <select required value={form.owner} disabled={busy} onChange={event=>form.setOwner(event.target.value)}>
    <option value="">담당자 선택</option>
    {recipients.map((user:Obj)=><option key={user.userId} value={user.userId}>{user.name} · {user.userId}</option>)}
   </select>
  </Field>
 </>;
}
function CompletionFields({form,busy}:{form:WorkDialogForm;busy:boolean}){
 return <>
  <Field label="실제로 수행·확인한 내용과 결과">
   <textarea required maxLength={4000} value={form.description} disabled={busy} onChange={event=>form.setDescription(event.target.value)}/>
  </Field>
  <Field label="수행 시각 (KST)">
   <input type="datetime-local" required value={form.performed} disabled={busy} onChange={event=>form.setPerformed(event.target.value)}/>
  </Field>
  <label className="check">
   <input type="checkbox" required checked={form.confirmed} disabled={busy} onChange={event=>form.setConfirmed(event.target.checked)}/>직접 수행·확인한 결과입니다.
  </label>
 </>;
}
function WorkDialog({item,mode,incident,onClose}:{item:Obj;mode:string;incident?:Incident;onClose:()=>void}){
 const policy=usePolicy();
 const incidentQuery=useQuery({
  queryKey:['incident',item.incidentId],
  queryFn:()=>api<Incident>('/api/incidents/'+item.incidentId),
  enabled:!incident,
 });
 const targetIncident=incident||incidentQuery.data;
 const form=useWorkDialogForm(item);
 const command=useWorkCommand(onClose);
 const busy=command.isPending;
 const isTeam=mode==='adopt'&&item.route==='TEAM_REQUEST';
 const title=dialogTitles(item,isTeam)[mode];
 const submit=()=>{
  if(command.isPending)return;
  const request=workDialogRequest(item,mode,form,targetIncident);
  if(request)command.mutate(request);
 };
 return <Modal title={title} onClose={onClose}>
  <form onSubmit={event=>{event.preventDefault();submit()}}><WorkCard item={item}/>
   {mode==='adopt'&&item.route==='MANAGER_APPROVAL'&&<Notice>
    담당자가 채택한 변경 계획을 관리자에게 전달합니다. 현재 단계에서 수행 완료로 기록되지 않습니다.
   </Notice>}
   {isTeam&&<TeamRecipientFields item={item} form={form} directory={policy.data?.directory||[]} busy={busy}/>}
   {['decline','approve','reject'].includes(mode)&&<Field label={mode==='reject'?'반려 사유 (필수)':mode==='decline'?'미채택 사유 (선택)':'검토 의견 (선택)'}>
    <textarea required={reasonRequired(mode)} value={form.comment} disabled={busy} onChange={event=>form.setComment(event.target.value)} maxLength={2000}/>
   </Field>}
   {mode==='unperformed'&&<Field label="미수행 사유">
    <textarea required value={form.comment} disabled={busy} onChange={event=>form.setComment(event.target.value)} maxLength={500}/>
   </Field>}
   {reasonRequired(mode)&&!form.comment.trim()&&<p className="field-error">{mode==='unperformed'?'사유를 입력해야 미수행으로 닫을 수 있습니다.':'사유를 입력해야 반려할 수 있습니다.'}</p>}
   {mode==='complete'&&<CompletionFields form={form} busy={busy}/>}
   {incidentQuery.error&&<ErrorMessage error={incidentQuery.error}/>} {command.error&&<ErrorMessage error={command.error}/>}
   <button className="button full" disabled={isDialogSubmitDisabled(mode,isTeam,busy,form,targetIncident)}>{busy?'처리 중…':title}</button>
  </form>
 </Modal>;
}
export function WorkManagerApprovals(){
 const {session}=useAuth();
 const [selected,setSelected]=useState<{item:Obj;mode:string}|null>(null);
 const approvals=useQuery({
  queryKey:['work-approvals'],
  queryFn:()=>api<Obj>('/api/work-items?box=approval'),
  enabled:session?.role==='MANAGER',
  refetchInterval:3000,
 });
 if(session?.role!=='MANAGER')return null;
 return <Panel title="변경 계획 승인 요청">
  {approvals.error?<ErrorMessage error={approvals.error}/>
   :approvals.data?.items?.length?approvals.data.items.map((item:Obj)=><WorkCard key={item.taskId} item={item}>
   <p className="small muted">요청자 · {item.requestedBy} · {item.processCode}</p>
   <Link className="text-button" to={'/incidents/'+item.incidentId+'?tab=actions'}>사건 근거 확인</Link>
   <div className="work-buttons">
    <button className="button" onClick={()=>setSelected({item,mode:'approve'})}>변경 계획 승인</button>
    <button className="button secondary" onClick={()=>setSelected({item,mode:'reject'})}>반려</button>
   </div>
  </WorkCard>):<Empty>검토할 변경 계획이 없습니다.</Empty>}
  {selected&&<WorkDialog item={selected.item} mode={selected.mode} onClose={()=>setSelected(null)}/>}
 </Panel>;
}
export function WorkInbox(){
 const {session}=useAuth();
 const [selected,setSelected]=useState<{item:Obj;mode:string}|null>(null);
 const myWork=useQuery({queryKey:['my-work'],queryFn:()=>api<Obj>('/api/work-items?box=mine'),refetchInterval:3000});
 const canComplete=(item:Obj)=>['READY','APPROVED'].includes(item.status)
  &&item.assigneeUserId===session?.user.userId
  &&canManageInvestigation(session,item.processCode);
 return <>
  <PageHeading kicker="MY WORK" title="내 할 일" description="채택한 확인 업무, 변경 승인과 다른 팀 요청의 진행 상황을 확인합니다."/>
  <Panel>
   {myWork.error?<ErrorMessage error={myWork.error}/>:myWork.data?.items?.length?myWork.data.items.map((item:Obj)=><WorkCard key={item.taskId} item={item}>
    <Link className="text-button" to={'/incidents/'+item.incidentId+'?tab=actions'}>사건의 확인·조치 보기</Link>
    {item.requestId&&<Link className="text-button" to={requestLink(item.requestId)}>요청·회신 확인</Link>}
    {canComplete(item)&&<button className="button" onClick={()=>setSelected({item,mode:'complete'})}>{completeLabel(item)}</button>}
   </WorkCard>):<Empty>채택한 업무가 없습니다.</Empty>}
  </Panel>
  {selected&&<WorkDialog item={selected.item} mode={selected.mode} onClose={()=>setSelected(null)}/>}
 </>;
}
