import {useState} from 'react';
import {Link} from 'react-router-dom';
import {useMutation,useQuery,useQueryClient} from '@tanstack/react-query';
import {CheckCircle2} from 'lucide-react';
import {api,write} from '../api/client';
import {type Incident,type Obj,when} from '../api/types';
import {canManageInvestigation} from '../domain/permissions';
import {useAuth} from '../auth';
import {Badge,Empty,ErrorMessage,Field,Modal,Notice,Panel,PageHeading} from './ui';

export const workRoutes:Record<string,string>={SELF_CHECK:'내 확인 업무',MANAGER_APPROVAL:'관리자 승인 필요',TEAM_REQUEST:'다른 팀 요청'};
export const workStates:Record<string,string>={PROPOSED:'채택 대기',DECLINED:'미채택',READY:'확인 진행 가능',WAITING_MANAGER:'관리자 승인 대기',APPROVED:'관리자 승인 완료',REJECTED:'관리자 반려',REQUESTED:'담당자 회신 대기',REQUEST_DECLINED:'담당자 요청 거절',COMPLETED:'결과 기록 완료'};
export const workText=(text:unknown)=>String(text??'').normalize('NFKC').toLowerCase().replace(/[\p{P}\p{Z}\s]/gu,'');
export const useIncidentWork=(incidentId:string)=>useQuery({queryKey:['work-items',incidentId],queryFn:()=>api<Obj>(`/api/incidents/${incidentId}/work-items`),refetchInterval:3000});
export const usePolicy=()=>useQuery({queryKey:['work-policy'],queryFn:()=>api<Obj>('/api/work-policy'),staleTime:60000});
export function useWorkCommand(onSaved:()=>void){const qc=useQueryClient();return useMutation({mutationFn:(operation:()=>Promise<unknown>)=>operation(),onSuccess:async()=>{await qc.invalidateQueries({predicate:query=>query.queryKey[0]!=='session'});onSaved()},onError:()=>qc.invalidateQueries({predicate:query=>['incident','work-items','my-work','work-approvals'].includes(String(query.queryKey[0]))})})}
export function WorkItemsPanel({incident,canManage}:{incident:Incident;canManage:boolean}){
 const {session}=useAuth();const q=useIncidentWork(incident.incidentId);
 const [selected,setSelected]=useState<{item:Obj;mode:string}|null>(null);
 const requests=useQuery({queryKey:['incident-requests',incident.incidentId],queryFn:()=>api<Obj>(`/api/incidents/${incident.incidentId}/check-requests`),refetchInterval:3000});
 const items:Obj[]=q.data?.items||[];const workKey=(item:Obj)=>item.operationCode+':'+workText(item.title);const proposed=items.filter(item=>item.status==='PROPOSED').filter((item,index,list)=>list.findIndex(other=>workKey(other)===workKey(item))===index&&!items.some(other=>!['PROPOSED','REQUEST_DECLINED'].includes(other.status)&&workKey(other)===workKey(item)));const adopted=items.filter(item=>item.route!=='TEAM_REQUEST'&&!['PROPOSED','DECLINED','REJECTED'].includes(item.status));const archived=items.filter(item=>['DECLINED','REJECTED'].includes(item.status));
 return <><Panel title="확인·조치 제안">
  {q.error?<ErrorMessage error={q.error}/>:proposed.length?proposed.map(item=><WorkCard key={item.taskId} item={item}><div className="work-buttons"><button className="button" disabled={!canManage||incident.status==='CLOSED'} onClick={()=>setSelected({item,mode:'adopt'})}>{item.route==='SELF_CHECK'?'내 확인 목록에 추가':item.route==='MANAGER_APPROVAL'?'채택하고 관리자 승인 요청':'요청 내용 검토·발송'}</button><button className="button secondary" disabled={!canManage||incident.status==='CLOSED'} onClick={()=>setSelected({item,mode:'decline'})}>채택 안 함</button></div></WorkCard>):<Empty>{q.isPending?'업무 제안을 불러오는 중…':'채택 대기 중인 제안이 없습니다. 분석과 추가 질문의 답변에서 제안을 바로 수락할 수 있습니다.'}</Empty>}
 </Panel><Panel title="분석·제안별 확인 및 조치">
  {adopted.length?adopted.map(item=><WorkCard key={item.taskId} item={item}>
   {item.approverUserId&&<p className="small muted">승인 담당 · {item.approverUserId}</p>}
   {item.status==='WAITING_MANAGER'&&<p className="work-waiting">관리자가 검토하고 있습니다. 승인 후 수행 결과를 기록할 수 있습니다.</p>}
   {['READY','APPROVED'].includes(item.status)&&item.assigneeUserId===session?.user.userId&&canManage&&<button className="button" disabled={incident.status==='CLOSED'} onClick={()=>setSelected({item,mode:'complete'})}><CheckCircle2 size={18}/>수행·확인 결과 기록</button>}
   {item.requestId&&<Link className="text-button" to={'/check-requests?request='+encodeURIComponent(item.requestId)}>요청·회신 확인</Link>}
   {item.actionId&&<p className="small muted">수행 기록 · {item.actionId}</p>}
  </WorkCard>):!requests.data?.items?.length&&<Empty>채택한 업무가 없습니다. 제안을 검토해 확인 목록에 추가하세요.</Empty>}
  {requests.error&&<ErrorMessage error={requests.error}/>}
  {(requests.data?.items||[]).map((request:Obj)=><TeamRequestResult key={request.requestId} request={request}/>)}
 </Panel>{archived.length>0&&<details className="work-archive"><summary>미채택·반려 제안 {archived.length}건</summary>{archived.map(item=><WorkCard key={item.taskId} item={item}/>)}</details>}
 {selected&&<WorkDialog item={selected.item} mode={selected.mode} incident={incident} onClose={()=>setSelected(null)}/>}
 </>;
}
function TeamRequestResult({request}:{request:Obj}) {
 const replies:Obj[]=request.replies||[];const replied=request.status==='REPLIED'&&replies.length>0;const declined=request.status==='DECLINED';
 return <article className="work-card team-result"><div className="work-card-header"><span className="work-route team_request">다른 팀 요청 · {request.targetProcessCode}</span><Badge value={replied?'COMPLETED':declined?'REJECTED':'REQUESTED'}>{replied?'담당자 확인·회신 완료':declined?'담당자 거절':request.status==='ACCEPTED'?'담당자 수락·확인 중':'담당자 수락 대기'}</Badge></div><h3>{request.question}</h3><p className="small muted">{request.equipmentId} · 담당 {request.ownerUserId} · 요청 {when(request.createdAt)} KST</p>
  {replies.map(reply=><div className="team-reply" key={reply.replyId}><strong>담당자 확인·조치 결과</strong><p>{reply.findings}</p><span className="small muted">{when(reply.createdAt)} KST · {reply.authorUserId}</span></div>)}
  {declined&&<div className="team-reply declined"><strong>거절 사유</strong><p>{request.declineReason}</p></div>}
  {replied&&<p className="small muted">요청 회신 완료 · 사건의 해결 효과는 별도 확인</p>}
  <Link className="text-button" to={'/check-requests?request='+encodeURIComponent(request.requestId)}>요청·회신 상세 보기</Link>
 </article>;
}
export function WorkCard({item,children}:{item:Obj;children?:React.ReactNode}){return <article className="work-card"><div className="work-card-header"><span className={'work-route '+item.route.toLowerCase()}>{workRoutes[item.route]}</span><Badge value={item.status}>{workStates[item.status]}</Badge></div><h3>{item.title}</h3><p>{item.reason}</p><div className="work-meta"><span>{item.operationLabel}</span><span>{when(item.createdAt)} KST</span></div>{item.decisionComment&&<p className="small">{item.status==='REQUEST_DECLINED'?'거절 사유':'검토 의견'} · {item.decisionComment}</p>}{children}</article>}
export function WorkDialog({item,mode,incident,onClose}:{item:Obj;mode:string;incident?:Incident;onClose:()=>void}){
 const policy=usePolicy();const incidentQuery=useQuery({queryKey:['incident',item.incidentId],queryFn:()=>api<Incident>('/api/incidents/'+item.incidentId),enabled:!incident});const i=incident||incidentQuery.data;
 const [comment,setComment]=useState('');const [description,setDescription]=useState('');const [confirmed,setConfirmed]=useState(false);const [target,setTarget]=useState(item.operationCode==='UPSTREAM_CHECK'?(item.processCode==='CMP'?'ETCH':'CVD'):item.processCode);const [owner,setOwner]=useState('');const [performed,setPerformed]=useState(new Date(Date.now()+9*3600000-60000).toISOString().slice(0,16));const cmd=useWorkCommand(onClose);
 const isTeam=mode==='adopt'&&item.route==='TEAM_REQUEST';const recipients=(policy.data?.directory||[]).filter((user:Obj)=>user.kind===(item.operationCode==='UPSTREAM_CHECK'?'PROCESS_OWNER':'MAINTENANCE')&&user.processScopes.includes(target));
 const title=({adopt:item.route==='MANAGER_APPROVAL'?' 채택·관리자 승인 요청':isTeam?'다른 팀 요청 발송':'내 확인 목록에 추가',decline:'제안 채택 안 함',approve:'변경 계획 승인',reject:'변경 계획 반려',complete:'수행·확인 결과 기록'} as Obj)[mode];
 const submit=()=>{
  if(cmd.isPending)return;
  if(mode==='adopt')cmd.mutate(()=>write(`/api/work-items/${item.taskId}/adoptions`,{expectedVersion:item.version,ownerUserId:owner,targetProcessCode:target}));
  else if(mode==='complete'&&i)cmd.mutate(()=>write(`/api/work-items/${item.taskId}/completions`,{description,performedAt:new Date(performed+'+09:00').toISOString(),confirmedByUser:confirmed,expectedVersion:item.version,expectedIncidentVersion:i.version}));
  else if(mode!=='complete')cmd.mutate(()=>write(`/api/work-items/${item.taskId}/decisions`,{decision:({decline:'DECLINE',approve:'APPROVE',reject:'REJECT'} as Obj)[mode],comment,expectedVersion:item.version}));
 };
 return <Modal title={title} onClose={onClose}><form onSubmit={event=>{event.preventDefault();submit()}}><WorkCard item={item}/>
  {mode==='adopt'&&item.route==='MANAGER_APPROVAL'&&<Notice>담당자가 채택한 변경 계획을 관리자에게 전달합니다. 현재 단계에서 수행 완료로 기록되지 않습니다.</Notice>}
  {isTeam&&<><Field label="요청 대상 공정"><select value={target} disabled={cmd.isPending||item.operationCode!=='UPSTREAM_CHECK'} onChange={e=>{setTarget(e.target.value);setOwner('')}}>{['CVD','ETCH','CMP'].filter(code=>item.operationCode!=='UPSTREAM_CHECK'||['CVD','ETCH','CMP'].indexOf(code)<['CVD','ETCH','CMP'].indexOf(item.processCode)).map(code=><option key={code}>{code}</option>)}</select></Field><Field label="수신 담당자"><select required value={owner} disabled={cmd.isPending} onChange={e=>setOwner(e.target.value)}><option value="">담당자 선택</option>{recipients.map((user:Obj)=><option key={user.userId} value={user.userId}>{user.name} · {user.userId}</option>)}</select></Field></>}
  {['decline','approve','reject'].includes(mode)&&<Field label={mode==='reject'?'반려 사유':'검토 의견'}><textarea required={mode==='reject'} value={comment} disabled={cmd.isPending} onChange={e=>setComment(e.target.value)} maxLength={2000}/></Field>}
  {mode==='complete'&&<><Field label="실제로 수행·확인한 내용과 결과"><textarea required maxLength={4000} value={description} disabled={cmd.isPending} onChange={e=>setDescription(e.target.value)}/></Field><Field label="수행 시각 (KST)"><input type="datetime-local" required value={performed} disabled={cmd.isPending} onChange={e=>setPerformed(e.target.value)}/></Field><label className="check"><input type="checkbox" required checked={confirmed} disabled={cmd.isPending} onChange={e=>setConfirmed(e.target.checked)}/>직접 수행·확인한 결과입니다.</label></>}
  {incidentQuery.error&&<ErrorMessage error={incidentQuery.error}/>} {cmd.error&&<ErrorMessage error={cmd.error}/>}<button className="button full" disabled={cmd.isPending||isTeam&&!owner||mode==='complete'&&(!i||!confirmed||!description.trim())||mode==='reject'&&!comment.trim()}>{cmd.isPending?'처리 중…':title}</button>
 </form></Modal>;
}
export function WorkManagerApprovals(){const {session}=useAuth();const [selected,setSelected]=useState<{item:Obj;mode:string}|null>(null);const q=useQuery({queryKey:['work-approvals'],queryFn:()=>api<Obj>('/api/work-items?box=approval'),enabled:session?.role==='MANAGER',refetchInterval:3000});if(session?.role!=='MANAGER')return null;return <Panel title="변경 계획 승인 요청">{q.error?<ErrorMessage error={q.error}/>:q.data?.items?.length?q.data.items.map((item:Obj)=><WorkCard key={item.taskId} item={item}><p className="small muted">요청자 · {item.requestedBy} · {item.processCode}</p><Link className="text-button" to={'/incidents/'+item.incidentId+'?tab=actions'}>사건 근거 확인</Link><div className="work-buttons"><button className="button" onClick={()=>setSelected({item,mode:'approve'})}>변경 계획 승인</button><button className="button secondary" onClick={()=>setSelected({item,mode:'reject'})}>반려</button></div></WorkCard>):<Empty>검토할 변경 계획이 없습니다.</Empty>}{selected&&<WorkDialog item={selected.item} mode={selected.mode} onClose={()=>setSelected(null)}/>}</Panel>}
export function WorkInbox(){const {session}=useAuth();const [selected,setSelected]=useState<{item:Obj;mode:string}|null>(null);const q=useQuery({queryKey:['my-work'],queryFn:()=>api<Obj>('/api/work-items?box=mine'),refetchInterval:3000});const received=useQuery({queryKey:['requests','received','ACCEPTED'],queryFn:()=>api<Obj>('/api/check-requests?box=received&status=ACCEPTED&size=100'),refetchInterval:5000});return <><PageHeading kicker="MY WORK" title="내 할 일" description="채택한 확인 업무, 수락한 요청, 변경 승인과 다른 팀 요청의 진행 상황을 확인합니다."/>{(received.error||received.data?.items?.length>0)&&<Panel title="수락한 요청 · 회신 필요">{received.error?<ErrorMessage error={received.error}/>:received.data!.items.map((r:Obj)=><article className="work-card" key={r.requestId}><div className="work-card-header"><span className="work-route team_request">받은 요청 · {r.incidentDisplayId}</span><Badge value="ACCEPTED"/></div><h3>{r.question}</h3><div className="work-meta"><span>요청 {r.requesterUserId}</span><span>{when(r.createdAt)} KST</span></div><Link className="button" to={'/check-requests?request='+encodeURIComponent(r.requestId)}>확인 결과 회신하기</Link></article>)}</Panel>}{(q.error||q.data?.items?.length||!received.data?.items?.length)&&<Panel>{q.error?<ErrorMessage error={q.error}/>:q.data?.items?.length?q.data.items.map((item:Obj)=><WorkCard key={item.taskId} item={item}><Link className="text-button" to={'/incidents/'+item.incidentId+'?tab=actions'}>사건의 확인·조치 보기</Link>{item.requestId&&<Link className="text-button" to={'/check-requests?request='+encodeURIComponent(item.requestId)}>요청·회신 확인</Link>}{['READY','APPROVED'].includes(item.status)&&item.assigneeUserId===session?.user.userId&&canManageInvestigation(session,item.processCode)&&<button className="button" onClick={()=>setSelected({item,mode:'complete'})}>수행·확인 결과 기록</button>}</WorkCard>):<Empty>채택한 업무나 수락한 요청이 없습니다.</Empty>}</Panel>}{selected&&<WorkDialog item={selected.item} mode={selected.mode} onClose={()=>setSelected(null)}/>}</>}
