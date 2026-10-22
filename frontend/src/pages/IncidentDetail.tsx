import {useEffect,useState,type Dispatch,type SetStateAction} from 'react';
import {Link,useParams,useSearchParams} from 'react-router-dom';
import {useQueries,useQuery,useQueryClient} from '@tanstack/react-query';
import {ArrowRight,ArrowUpRight,FileText,Send,RefreshCw,ChevronLeft} from 'lucide-react';
import {api,API_MODE,write} from '../api/client';
import {fmt,labels,when,type Incident,type Obj} from '../api/types';
import {useAuth} from '../auth';
import {Badge,Empty,ErrorMessage,Field,Modal,Notice,PageHeading,Panel} from '../components/ui';
import {ActionWorkspace} from '../components/ActionWorkspace';
import {loadQuestionThread} from '../domain/questionThreads';
import {incidentStage} from '../domain/actionSources';
import {AnalysisRequestStatus} from '../components/AnalysisRequestStatus';
import {ProposedWorkList} from '../components/WorkItems';
import {InvestigationConversation} from '../components/InvestigationConversation';
import {conversationEntries} from '../domain/conversation';
import {canManageInvestigation} from '../domain/permissions';
import {ErrorMessageIfFailed,useCommand} from './workflowShared';
const IN_PROGRESS_STATES=['QUEUED','RUNNING','WAITING_APPROVAL','WAITING_REPLY'];
const STREAM_EVENTS=[
 'snapshot','job.status','analysis.result','approval.created','approval.decided','check-request.replied',
 'action.recorded','action.verified','report.ready','report.confirmed','incident.updated','work-item.updated',
];
const DETAIL_TABS=[['analysis','분석과 추가 질문'],['evidence','관측 근거'],['actions','검토·조치·효과'],['reports','보고서'],['timeline','조사 이력']];
const nowInKstInput=()=>new Date(Date.now()+9*3600000-60000).toISOString().slice(0,16);
type Command=ReturnType<typeof useCommand>;
type TrackedJob={incidentId:string;jobId:string};
function useStream(id:string){
 const queryClient=useQueryClient();
 const [connected,setConnected]=useState(false);
 useEffect(()=>{
  if(API_MODE==='mock')return;
  const source=new EventSource(`/api/incidents/${encodeURIComponent(id)}/stream`,{withCredentials:true});
  source.onopen=()=>setConnected(true);
  source.onerror=()=>setConnected(false);
  const update=()=>queryClient.invalidateQueries({
   predicate:query=>query.queryKey.includes(id)
    ||['job','approvals','approval','requests','request','dashboard'].includes(String(query.queryKey[0])),
  });
  for(const eventName of STREAM_EVENTS)source.addEventListener(eventName,update);
  return()=>{
   source.close();
   setConnected(false);
  };
 },[id,queryClient]);
 return connected;
}
/** 사건 · 분석 · 진행 중 작업 · 조치 · 보고서 조회. 작업 상태가 바뀌면 사건 화면 데이터를 다시 불러온다 */
function useInvestigationData(id:string){
 const queryClient=useQueryClient();
 const incidentQuery=useQuery({queryKey:['incident',id],queryFn:()=>api<Incident>('/api/incidents/'+id),refetchInterval:2000});
 const incident=incidentQuery.data;
 const analysis=useQuery({
  queryKey:['analysis',id],
  queryFn:()=>api<Obj>(`/api/incidents/${id}/analysis-results?latest=false`),
  retry:false,
  refetchInterval:5000,
 });
 const [trackedJob,setTrackedJob]=useState<TrackedJob|null>(null);
 useEffect(()=>{
  if(incident?.activeJobId)setTrackedJob({incidentId:id,jobId:incident.activeJobId});
 },[id,incident?.activeJobId]);
 const jobId=(trackedJob?.incidentId===id?trackedJob.jobId:null)||incident?.activeJobId||analysis.data?.jobId;
 const job=useQuery({
  queryKey:['job',jobId],
  queryFn:()=>api<Obj>('/api/jobs/'+jobId),
  enabled:!!jobId,
  refetchInterval:query=>IN_PROGRESS_STATES.includes(query.state.data?.status)?2000:false,
 });
 useEffect(()=>{
  if(!job.data)return;
  for(const key of ['incident','analysis','actions','reports','work-items'])queryClient.invalidateQueries({queryKey:[key,id]});
  queryClient.invalidateQueries({queryKey:['approvals']});
  queryClient.invalidateQueries({queryKey:['requests']});
 },[job.data?.jobId,job.data?.status,id,queryClient]);
 const actions=useQuery({queryKey:['actions',id],queryFn:()=>api<Obj>(`/api/incidents/${id}/actions`),refetchInterval:5000});
 const reports=useQuery({queryKey:['reports',id],queryFn:()=>api<Obj>(`/api/incidents/${id}/reports`),refetchInterval:3000});
 return {incidentQuery,incident,analysis,jobId,job,setTrackedJob,actions,reports};
}
/** 승인 대기 제안 · 질문 스레드 · 지난 분석 결과 */
function useConversation(id:string,incident:Incident|undefined,analysis:Obj|undefined,activeJob:Obj|null|undefined){
 const approvalId=activeJob?.waitingRef?.type==='APPROVAL'?activeJob.waitingRef.id:null;
 const proposal=useQuery({
  queryKey:['approval',approvalId],
  queryFn:()=>api<Obj>('/api/approvals/'+approvalId),
  enabled:!!approvalId&&activeJob?.status==='WAITING_APPROVAL',
  refetchInterval:2000,
 });
 const questions=conversationEntries(incident?.timeline,analysis?.history||[],activeJob);
 const questionJobs=useQueries({queries:questions.map(entry=>({
  queryKey:['question-thread',id,entry.jobId],
  queryFn:()=>loadQuestionThread(entry.jobId,threadJobId=>api<Obj>('/api/jobs/'+threadJobId)),
  refetchInterval:(query:any)=>IN_PROGRESS_STATES.includes(query.state.data?.latestJob?.status)?2000:false,
 }))});
 const answerResults=useQueries({queries:(analysis?.history||[])
  .filter((result:Obj)=>result.resultId!==analysis?.resultId)
  .map((result:Obj)=>({
   queryKey:['analysis-source',id,result.resultId],
   queryFn:()=>api<Obj>(`/api/incidents/${id}/analysis-results?resultId=${encodeURIComponent(result.resultId)}`),
  }))});
 return {proposal,questions,questionJobs,answerResults};
}
type DetailContext={
 id:string;
 incident:Incident;
 command:Command;
 blocked:boolean;
 setStartingBasic:(starting:boolean)=>void;
 setRequestedBasic:(job:Obj)=>void;
 setTrackedJob:Dispatch<SetStateAction<TrackedJob|null>>;
};
async function requestRerun(context:DetailContext){
 const {id,incident,command,blocked}=context;
 if(blocked||command.isPending)return;
 context.setStartingBasic(true);
 try{
  await command.exec(async()=>{
   const accepted=await write<Obj>(`/api/incidents/${id}/basic-analysis-jobs`,{expectedIncidentVersion:incident.version,reason:'사용자 재실행'});
   context.setRequestedBasic({...accepted,kind:'BASIC',createdAt:new Date().toISOString()});
   context.setTrackedJob({incidentId:id,jobId:accepted.jobId});
  },undefined,'재분석 요청을 접수했습니다.');
 }finally{
  context.setStartingBasic(false);
 }
}
function submitQuestion(context:DetailContext,question:string,clearQuestion:()=>void){
 const {id,incident,command,blocked}=context;
 if(blocked||command.isPending)return;
 command.exec(
  ()=>write(`/api/incidents/${id}/questions`,{question,scope:'AUTO',expectedIncidentVersion:incident.version}),
  clearQuestion,
  '질문을 접수했습니다.',
 );
}
function transitionIncident({id,incident,command}:DetailContext,targetStatus:string){
 if(!confirm(`${labels[targetStatus]} 상태로 전환하시겠습니까?`))return;
 command.exec(()=>write(`/api/incidents/${id}/transitions`,{
  targetStatus,reason:'관리자가 화면에서 상태 전환 확인',expectedVersion:incident.version,evidenceIds:[],
 }));
}
function requestReportDraft({id,incident,command}:DetailContext){
 command.exec(()=>write(`/api/incidents/${id}/report-jobs`,{expectedIncidentVersion:incident.version,baseReportVersion:null}));
}
/** 진행 중인 분석 · 승인 · 회신이 있거나 운영 권한이 없으면 새 요청을 막는다 */
function investigationStatus(incident:Incident,activeJob:Obj|null|undefined,startingBasic:boolean,canManage:boolean){
 const state=activeJob?.status||incident.jobStatus;
 const blocked=startingBasic||!canManage||incident.status==='CLOSED'||IN_PROGRESS_STATES.includes(state||'');
 const rerunning=startingBasic||activeJob?.kind==='BASIC'&&['QUEUED','RUNNING'].includes(state||'');
 return {canManage,blocked,rerunning};
}
export function IncidentDetail(){
 const {id=''}=useParams();
 const [detailParams]=useSearchParams();
 const {session}=useAuth();
 const connected=useStream(id);
 const data=useInvestigationData(id);
 const [tab,setTab]=useState(detailParams.get('tab')||'analysis');
 const [question,setQuestion]=useState('');
 const [modal,setModal]=useState('');
 const [startingBasic,setStartingBasic]=useState(false);
 const [requestedBasic,setRequestedBasic]=useState<Obj|null>(null);
 const command=useCommand();
 const {incident}=data;
 const activeJob=data.job.data||(requestedBasic?.jobId===data.jobId?requestedBasic:incident?.activeJob);
 const conversation=useConversation(id,incident,data.analysis.data,activeJob);
 const [verification,setVerification]=useState<Obj|null>(null);
 if(data.incidentQuery.isError)return <ErrorMessage error={data.incidentQuery.error}/>;
 if(!incident)return <Empty>사건을 불러오는 중…</Empty>;
 const {canManage,blocked,rerunning}=investigationStatus(incident,activeJob,startingBasic,canManageInvestigation(session,incident.processCode));
 const context:DetailContext={id,incident,command,blocked,setStartingBasic,setRequestedBasic,setTrackedJob:data.setTrackedJob};
 const stage=incidentStage(incident,activeJob);
 return <><IncidentHeader incident={incident} stage={stage} connected={connected}/>
  {command.message&&<Notice tone={command.isError?'error':'info'}>{command.message}</Notice>}
  {tab!=='analysis'&&activeJob&&<AnalysisRequestStatus job={activeJob} approval={conversation.proposal.data} error={conversation.proposal.error}/>}
  <DetailTabs tab={tab} onChange={setTab}/>
  {tab==='analysis'&&<AnalysisTab
   incident={incident} stage={stage} activeJob={activeJob} latestAnalysis={data.analysis.data} conversation={conversation} command={command}
   startingBasic={startingBasic} rerunning={rerunning} canManage={canManage} blocked={blocked} question={question} onQuestionChange={setQuestion}
   onRerun={()=>requestRerun(context)} onAsk={()=>submitQuestion(context,question,()=>setQuestion(''))} onReviewActions={()=>setTab('actions')}
  />}
  {tab==='evidence'&&<Evidence incident={incident}/>}
  {tab==='actions'&&<ActionWorkspace canManage={canManage} incident={incident} analysis={data.analysis.data}
   actions={data.actions.data?.items||[]} actionsError={data.actions.error} onVerify={setVerification}/>}
  {tab==='reports'&&<ReportsTab reports={data.reports.data?.items} disabled={blocked||command.isPending} onRequest={()=>requestReportDraft(context)}/>}
  {tab==='timeline'&&<TimelineTab timeline={incident.timeline||[]}/>}
  {session?.role==='MANAGER'&&<ManagerBar incident={incident} pending={command.isPending} onTransition={target=>transitionIncident(context,target)}/>}
  {modal&&<RecordModal type={modal} incident={incident} onClose={()=>setModal('')}/>}
  {verification&&<VerificationModal verification={verification} command={command} onClose={()=>setVerification(null)}/>}
 </>;
}
function IncidentHeader({incident,stage,connected}:{incident:Incident;stage:{code:string;label:string};connected:boolean}){
 return <><Link className="back-link" to="/incidents"><ChevronLeft size={17}/> 이상 조사 목록</Link><PageHeading
  kicker={`${incident.displayId} · ${incident.processCode}`}
  title={incident.title}
  description={`${incident.lotId} · ${incident.equipmentId} · ${when(incident.detectedAt)} KST`}
  extra={<Badge value={stage.code}>{stage.label}</Badge>}
 /><div className="incident-summary">
  <Triangle/>
  <p>{incident.summary||'원시 데이터와 분석 결과를 함께 확인해 주세요.'}</p>
  <span className="small muted">{connected?'SSE 연결됨':API_MODE==='mock'?'모의 분석 · 상태 자동 갱신':'상태 조회로 복구 중'}</span>
 </div></>;
}
function DetailTabs({tab,onChange}:{tab:string;onChange:(tab:string)=>void}){
 return <div className="tabs">
  {DETAIL_TABS.map(([value,title])=><button key={value} className={tab===value?'active':''} onClick={()=>onChange(value)}>{title}</button>)}
 </div>;
}
type AnalysisTabProps={
 incident:Incident;
 stage:{code:string;label:string};
 activeJob?:Obj|null;
 latestAnalysis?:Obj;
 conversation:ReturnType<typeof useConversation>;
 startingBasic:boolean;
 rerunning:boolean;
 canManage:boolean;
 blocked:boolean;
 command:Command;
 question:string;
 onQuestionChange:(question:string)=>void;
 onRerun:()=>void;
 onAsk:()=>void;
 onReviewActions:()=>void;
};
function AnalysisTab(props:AnalysisTabProps){
 const {incident,stage,activeJob,latestAnalysis,conversation,command}=props;
 const {proposal,questions,questionJobs,answerResults}=conversation;
 const rerunButton=<button className="button secondary" disabled={props.blocked||command.isPending} onClick={props.onRerun}>
  <RefreshCw size={18}/>{props.rerunning?'재분석 중…':'기본 분석 재요청'}
 </button>;
 return <div className="analysis-grid"><Panel title="조사 내용" className="conversation-panel" extra={rerunButton}>
  <InvestigationConversation
   entries={questions}
   threads={questionJobs.map(query=>query.data)}
   results={[...(latestAnalysis?[latestAnalysis]:[]),...answerResults.flatMap(query=>query.data?[query.data]:[])]}
   activeJob={activeJob}
   proposal={proposal.data}
   startingBasic={props.startingBasic}
  />
  {questionJobs.some(query=>query.error)&&<ErrorMessage error={questionJobs.find(query=>query.error)?.error}/>}
  {answerResults.some(query=>query.error)&&<ErrorMessage error={answerResults.find(query=>query.error)?.error}/>}
  {proposal.error&&<ErrorMessage error={proposal.error}/>}
  <ProposedWorkList incident={incident} canManage={props.canManage}
   results={[...(latestAnalysis?[latestAnalysis]:[]),...answerResults.flatMap(query=>query.data?[query.data]:[])]}/>
  <QuestionComposer
   canManage={props.canManage}
   blocked={props.blocked}
   pending={command.isPending}
   question={props.question}
   onChange={props.onQuestionChange}
   onSubmit={props.onAsk}
  />
  </Panel><div className="side-stack">
   <Panel title="다음 확인">
    <button className="button secondary full" disabled={!props.canManage||incident.status==='CLOSED'} onClick={props.onReviewActions}>확인·조치 제안 검토</button>
    <Link className="text-button" to="/approvals">승인 제안 확인 <ArrowRight size={17}/></Link>
   </Panel>
   <Panel title="실행 정보">
    <dl className="facts">
     <div><dt>사건 버전</dt><dd>v{incident.version}</dd></div>
     <div><dt>분석 상태</dt><dd><Badge value={stage.code}>{stage.label}</Badge></dd></div>
     <div><dt>데이터 버전</dt><dd>{latestAnalysis?.dataVersion||'—'}</dd></div>
    </dl>
   </Panel>
  </div></div>;
}
function QuestionComposer({canManage,blocked,pending,question,onChange,onSubmit}:{
 canManage:boolean;blocked:boolean;pending:boolean;question:string;onChange:(question:string)=>void;onSubmit:()=>void;
}){
 const placeholder=!canManage
  ?'담당 공정 운영자만 질문할 수 있습니다.'
  :blocked?'진행 중인 분석·승인·회신이 끝나면 질문할 수 있습니다.':'예: 이전 Etch 공정의 영향도 확인해줘.';
 return <div className="conversation-composer">
  <h3>추가 질문</h3>
  {!canManage&&<Notice>조회 전용입니다. 배정받은 확인·정비 요청은 받은 요청에서 회신하세요.</Notice>}
  <form onSubmit={event=>{event.preventDefault();onSubmit()}}>
   <textarea
    aria-label="추가 질문"
    disabled={blocked||pending}
    placeholder={placeholder}
    value={question}
    onChange={event=>onChange(event.target.value)}
    maxLength={2000}
    required
   />
   <div className="form-actions">
    <span className="small muted">{question.length}/2000</span>
    <button className="button" disabled={!question.trim()||blocked||pending}>
     <Send size={18}/>{pending?'전송 중…':'질문 보내기'}
    </button>
   </div>
  </form>
 </div>;
}
function ReportsTab({reports,disabled,onRequest}:{reports?:Obj[];disabled:boolean;onRequest:()=>void}){
 const requestButton=<button className="button" disabled={disabled} onClick={onRequest}><FileText size={18}/>초안 생성 요청</button>;
 return <Panel title="조사 보고서" extra={requestButton}>
  <p className="muted">관측·회신·조치·효과 확인 결과를 요약합니다.</p>
  {reports?.length?reports.map((report:Obj)=><Link className="report-row" key={report.reportId} to={'/reports/'+report.reportId}>
   <FileText/>
   <div>
    <strong>{report.title}</strong>
    <span className="table-sub">버전 {report.version} · 수정 {report.contentRevision} · {when(report.createdAt)}</span>
   </div>
   <Badge value={report.status}/>
   <ArrowUpRight size={20}/>
  </Link>):<Empty>생성된 보고서가 없습니다.</Empty>}
 </Panel>;
}
function TimelineTab({timeline}:{timeline:Obj[]}){
 return <Panel title="조사 이력">
  <ol className="timeline">
   {[...timeline].reverse().map((entry,index)=><li key={index}>
    <span className="small muted">{when(entry.at)}</span>
    <strong>{entry.message}</strong>
    <span className="small muted">{entry.actorId}</span>
   </li>)}
  </ol>
 </Panel>;
}
function ManagerBar({incident,pending,onTransition}:{incident:Incident;pending:boolean;onTransition:(targetStatus:string)=>void}){
 const targets=incident.status==='ON_HOLD'?['INVESTIGATING','CLOSED']:['ON_HOLD','CLOSED'];
 return <div className="manager-bar">
  <span className="small muted">관리자 상태 전환</span>
  {targets.map(targetStatus=><button
   key={targetStatus}
   className="button secondary"
   disabled={pending||incident.status==='CLOSED'}
   onClick={()=>onTransition(targetStatus)}
  >{labels[targetStatus]}</button>)}
 </div>;
}
/** 조치 효과 기록 — 열 때마다 빈 입력으로 시작한다 */
function VerificationModal({verification,command,onClose}:{verification:Obj;command:Command;onClose:()=>void}){
 const [rationale,setRationale]=useState('');
 const [outcome,setOutcome]=useState('');
 const [confirmed,setConfirmed]=useState(false);
 const save=()=>command.exec(
  ()=>write(`/api/actions/${verification.actionId}/verifications`,{
   outcome,rationale,confirmedByUser:confirmed,expectedVersion:verification.version,evidenceIds:[],
  }),
  onClose,
 );
 return <Modal title="확인·효과 기록" onClose={onClose}>
  <p className="small muted">{verification.relatedSource?.title||'별도 기록'}</p>
  <p>{verification.description}</p>
  <form onSubmit={event=>{event.preventDefault();save()}}>
   <Field label="확인 결과">
    <select required value={outcome} onChange={event=>setOutcome(event.target.value)}>
     <option value="">확인 결과 선택</option>
     {['IMPROVED','UNCHANGED','INCONCLUSIVE'].map(code=><option key={code} value={code}>{labels[code]}</option>)}
    </select>
   </Field>
   <Field label="확인 근거">
    <textarea required value={rationale} onChange={event=>setRationale(event.target.value)} placeholder="직접 확인한 후속 결과와 남은 사항을 기록해 주세요."/>
   </Field>
   <label className="check">
    <input type="checkbox" required checked={confirmed} onChange={event=>setConfirmed(event.target.checked)}/>실제 확인한 결과임을 확인했습니다.
   </label>
   <ErrorMessageIfFailed command={command}/>
   <button className="button full" disabled={command.isPending}>효과 확인 저장</button>
  </form>
 </Modal>;
}
function Triangle(){return <span className="incident-mark">!</span>}
function RequestTargetFields({incident,target,owner,owners,onTargetChange,onOwnerChange}:{
 incident:Incident;target:string;owner:string;owners:Obj[];onTargetChange:(target:string)=>void;onOwnerChange:(owner:string)=>void;
}){
 const ownerOptions=owners.filter((user:Obj)=>user.kind==='PROCESS_OWNER'&&user.processScopes.includes(target));
 return <>
  <Notice>승인 후 담당자에게 전달됩니다.</Notice>
  <Field label="대상 공정">
   <select value={target} onChange={event=>onTargetChange(event.target.value)}>
    {['CVD','ETCH','CMP'].filter(processCode=>processCode!==incident.processCode).map(processCode=><option key={processCode}>{processCode}</option>)}
   </select>
  </Field>
  <Field label="수신 담당자">
   <select value={owner} onChange={event=>onOwnerChange(event.target.value)} required>
    <option value="">담당자 선택</option>
    {ownerOptions.map((user:Obj)=><option key={user.userId} value={user.userId}>{user.name} · {user.userId}</option>)}
   </select>
  </Field>
 </>;
}
function ActionTimeFields({performed,confirmed,onPerformedChange,onConfirmedChange}:{
 performed:string;confirmed:boolean;onPerformedChange:(performed:string)=>void;onConfirmedChange:(confirmed:boolean)=>void;
}){
 return <>
  <Field label="수행 시각 (KST)">
   <input type="datetime-local" value={performed} onChange={event=>onPerformedChange(event.target.value)} required/>
  </Field>
  <label className="check">
   <input type="checkbox" required checked={confirmed} onChange={event=>onConfirmedChange(event.target.checked)}/>수행 내용을 확인했습니다.
  </label>
 </>;
}
function recordRequest(incident:Incident,draft:{type:string;description:string;owner:string;target:string;performed:string;confirmed:boolean}){
 if(draft.type==='request'){
  return write(`/api/incidents/${incident.incidentId}/proposals`,{
   actionType:'CHECK_REQUEST',
   payload:{ownerUserId:draft.owner,targetProcessCode:draft.target,question:draft.description},
   rationale:'사용자 검토를 위한 확인 요청 제안',
   expectedIncidentVersion:incident.version,
   evidenceIds:incident.runIds||[],
  });
 }
 return write(`/api/incidents/${incident.incidentId}/actions`,{
  actionType:'MANUAL_MAINTENANCE',
  description:draft.description,
  performedAt:new Date(draft.performed+'+09:00').toISOString(),
  confirmedByUser:draft.confirmed,
  expectedIncidentVersion:incident.version,
  evidenceIds:[],
 });
}
function RecordModal({type,incident,onClose}:{type:string;incident:Incident;onClose:()=>void}){
 const [description,setDescription]=useState('');
 const [owner,setOwner]=useState(API_MODE==='mock'?'demo-etch':'');
 const [target,setTarget]=useState(incident.processCode==='ETCH'?'CVD':'ETCH');
 const [confirmed,setConfirmed]=useState(false);
 const [performed,setPerformed]=useState(nowInKstInput());
 const owners=useQuery({queryKey:['work-policy'],queryFn:()=>api<Obj>('/api/work-policy'),enabled:type==='request'});
 const command=useCommand();
 useEffect(()=>{if(command.isSuccess)onClose()},[command.isSuccess]);
 const isRequest=type==='request';
 const chooseTarget=(processCode:string)=>{
  setTarget(processCode);
  if(API_MODE==='mock')setOwner('demo-'+processCode.toLowerCase());
 };
 return <Modal title={isRequest?'다른 공정 확인 요청 제안':'실제 조치 기록'} onClose={onClose}>
  <form onSubmit={event=>{event.preventDefault();command.exec(()=>recordRequest(incident,{type,description,owner,target,performed,confirmed}))}}>
   {isRequest&&<RequestTargetFields
    incident={incident} target={target} owner={owner} owners={owners.data?.directory||[]}
    onTargetChange={chooseTarget} onOwnerChange={setOwner}
   />}
   <Field label={isRequest?'확인할 내용':'실제로 수행한 조치'}>
    <textarea value={description} onChange={event=>setDescription(event.target.value)} required maxLength={isRequest?2000:4000}/>
   </Field>
   {type==='action'&&<ActionTimeFields performed={performed} confirmed={confirmed} onPerformedChange={setPerformed} onConfirmedChange={setConfirmed}/>}
   {command.isError&&<ErrorMessage error={command.error}/>}
   <button className="button full" disabled={command.isPending}>{isRequest?'검토할 제안 저장':'조치 기록 저장'}</button>
  </form>
 </Modal>;
}
function RouteRuns({runs}:{runs?:Obj[]}){
 return <div className="route-grid">
  {runs?.map((run:Obj)=><div key={run.runId}>
   <span className="eyebrow">{run.processCode}</span>
   <h3>{run.equipmentId}</h3>
   <p className="small">{run.recipeId}</p>
   <p className="small muted">{when(run.startedAt||run.runStart)}</p>
  </div>)}
 </div>;
}
function MetrologyTable({items}:{items?:Obj[]}){
 return <div className="table-wrap">
  <table>
   <thead>
    <tr>
     <th>공정</th>
     <th>웨이퍼</th>
     <th>측정 위치</th>
     <th>측정값</th>
     <th>측정 시각</th>
    </tr>
   </thead>
   <tbody>
    {items?.map((measurement:Obj,index:number)=><tr key={measurement.measurementId+index}>
     <td>{measurement.stage}</td>
     <td>{measurement.waferId}</td>
     <td>{measurement.siteId}</td>
     <td>{fmt(measurement.value)} {measurement.unit}</td>
     <td>{when(measurement.measuredAt)}</td>
    </tr>)}
   </tbody>
  </table>
 </div>;
}
function Evidence({incident}:{incident:Incident}){
 const metrology=useQuery({queryKey:['metrology',incident.incidentId],queryFn:()=>api<Obj>(`/api/incidents/${incident.incidentId}/metrology`)});
 const changes=useQuery({queryKey:['changes',incident.incidentId],queryFn:()=>api<Obj>(`/api/incidents/${incident.incidentId}/changes`)});
 const route=useQuery({
  queryKey:['route',incident.incidentId],
  queryFn:()=>api<Obj>(`/api/wafers/${incident.waferIds[0]}/route`),
  enabled:!!incident.waferIds[0],
 });
 return <div className="side-stack">
  <Panel title="웨이퍼 공정 경로">
   <p className="muted">{incident.waferIds.join(', ')}</p>
   <RouteRuns runs={route.data?.runs}/>
   {route.isError&&<ErrorMessage error={route.error}/>}
  </Panel>
  <Panel title="위치별 계측 근거">
   <MetrologyTable items={metrology.data?.items}/>
   {metrology.isError&&<ErrorMessage error={metrology.error}/>}
  </Panel>
  <Panel title="관련 변경 이력">
   {changes.data?.items?.length?changes.data.items.map((change:Obj)=><div className="record" key={change.changeId}>
    <h3>{change.equipmentId}</h3>
    <p>{change.summary||change.description}</p>
    <span className="small muted">{when(change.occurredAt)}</span>
   </div>):<Empty>제공된 변경 이력이 없습니다.</Empty>}
  </Panel>
 </div>;
}
