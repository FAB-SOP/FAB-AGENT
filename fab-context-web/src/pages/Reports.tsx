import {useEffect,useState,type ReactNode} from 'react';
import {Link,useParams} from 'react-router-dom';
import {useQueries,useQuery} from '@tanstack/react-query';
import {ArrowUpRight,FileText,ChevronLeft,Download} from 'lucide-react';
import {api,downloadReport,write} from '../api/client';
import {when,type Incident,type Obj,type Page} from '../api/types';
import {useAuth} from '../auth';
import {Badge,Empty,ErrorMessage,Field,Notice,PageHeading,Panel} from '../components/ui';
import {canManageInvestigation} from '../domain/permissions';
import {useCommand} from './workflowShared';
export function ReportList({process}:{process:string}){
 const incidents=useQuery({
  queryKey:['incidents',process],
  queryFn:()=>api<Page<Incident>>('/api/incidents?processCode='+process+'&size=100'),
 });
 const reportQueries=useQueries({queries:(incidents.data?.items||[]).map(incident=>({
  queryKey:['reports',incident.incidentId],
  queryFn:()=>api<Obj>(`/api/incidents/${incident.incidentId}/reports`),
 }))});
 const reports=reportQueries.flatMap(query=>query.data?.items||[]);
 return <>
  <PageHeading kicker="REPORTS & RECORDS" title="조사 보고서" description="사건별 초안을 검토·수정하고, 확정된 버전을 보존합니다."/>
  <Panel>
   {reports.length?reports.map(report=><Link className="report-row" to={'/reports/'+report.reportId} key={report.reportId}>
    <FileText/>
    <div>
     <strong>{report.title}</strong>
     <span className="table-sub">v{report.version} · {when(report.createdAt)}</span>
    </div>
    <Badge value={report.status}/>
    <ArrowUpRight/>
   </Link>):<Empty>아직 보고서가 없습니다. 이상 조사 → 보고서에서 초안 생성을 요청해 주세요.</Empty>}
  </Panel>
 </>;
}
/** 불러온 보고서를 편집 상태로 옮기고, 판본 · 상태가 바뀌면 다시 불러온 내용으로 되돌린다 */
function useReportDraft(report:Obj|undefined){
 const [title,setTitle]=useState('');
 const [sections,setSections]=useState<Obj[]>([]);
 const [review,setReview]=useState(false);
 const [dirty,setDirty]=useState(false);
 useEffect(()=>{
  if(!report)return;
  setTitle(report.title);
  setSections(report.sections);
  setReview(false);
  setDirty(false);
 },[report?.contentRevision,report?.status]);
 const editTitle=(value:string)=>{
  setTitle(value);
  setDirty(true);
 };
 const editSection=(sectionIndex:number,body:string)=>{
  setSections(previous=>previous.map((section,index)=>index===sectionIndex?{...section,body}:section));
  setDirty(true);
 };
 return {title,sections,review,setReview,dirty,editTitle,editSection};
}
function saveReportDraft(reportId:string,title:string,sections:Obj[],contentRevision:number){
 return write('/api/reports/'+reportId,{
  title,
  sections:sections.map(section=>({key:section.key,title:section.title,body:section.body||section.content||''})),
  expectedContentRevision:contentRevision,
 },'PATCH');
}
function ReportForm({title,sections,locked,onTitleChange,onSectionChange,actions}:{
 title:string;
 sections:Obj[];
 locked:boolean;
 onTitleChange:(title:string)=>void;
 onSectionChange:(index:number,body:string)=>void;
 actions:ReactNode;
}){
 return <Panel>
  <Field label="보고서 제목">
   <input value={title} disabled={locked} onChange={event=>onTitleChange(event.target.value)}/>
  </Field>
  {sections.map((section,index)=><Field key={section.key||index} label={section.title}>
   <textarea
    className="report-section"
    value={section.body||section.content||''}
    disabled={locked}
    onChange={event=>onSectionChange(index,event.target.value)}
   />
  </Field>)}
  <div className="form-actions">
   {actions}
  </div>
 </Panel>;
}
function ConfirmPanel({review,dirty,pending,onReviewChange,onConfirm}:{
 review:boolean;dirty:boolean;pending:boolean;onReviewChange:(review:boolean)=>void;onConfirm:()=>void;
}){
 return <Panel title="검토 후 확정">
  <label className="check">
   <input type="checkbox" checked={review} onChange={event=>onReviewChange(event.target.checked)}/>보고서 내용을 검토했습니다.
  </label>
  {dirty&&<p className="warning-text">먼저 수정 내용을 저장해 주세요.</p>}
  <button className="button" disabled={!review||dirty||pending} onClick={onConfirm}>검토한 버전 확정</button>
 </Panel>;
}
export function ReportEditor(){
 const {session}=useAuth();
 const {id=''}=useParams();
 const reportQuery=useQuery({queryKey:['report',id],queryFn:()=>api<Obj>('/api/reports/'+id)});
 const reportIncident=useQuery({
  queryKey:['incident',reportQuery.data?.incidentId],
  queryFn:()=>api<Incident>('/api/incidents/'+reportQuery.data!.incidentId),
  enabled:!!reportQuery.data?.incidentId,
 });
 const command=useCommand();
 const draft=useReportDraft(reportQuery.data);
 if(reportQuery.isError)return <ErrorMessage error={reportQuery.error}/>;
 if(!reportQuery.data)return <Empty>보고서를 불러오는 중…</Empty>;
 const report=reportQuery.data;
 const editable=canManageInvestigation(session,reportIncident.data?.processCode||'');
 const locked=!editable||report.status==='CONFIRMED';
 const saveDraft=()=>command.exec(()=>saveReportDraft(id,draft.title,draft.sections,report.contentRevision));
 const confirmReport=()=>{
  if(!confirm('이 버전의 보고서를 확정하시겠습니까?'))return;
  command.exec(()=>write(`/api/reports/${id}/confirmations`,{expectedContentRevision:report.contentRevision,reviewConfirmed:true}));
 };
 return <>
  <Link className="back-link" to={'/incidents/'+report.incidentId}><ChevronLeft size={17}/>사건으로 돌아가기</Link>
  <PageHeading
   kicker={`REPORT VERSION ${report.version} · REVISION ${report.contentRevision}`}
   title="조사 보고서 검토"
   description="내용을 검토·수정한 뒤 확정합니다."
   extra={<Badge value={report.status}/>}
  />
  {command.message&&<Notice tone={command.isError?'error':'info'}>{command.message}</Notice>}
  <ReportForm title={draft.title} sections={draft.sections} locked={locked} onTitleChange={draft.editTitle} onSectionChange={draft.editSection} actions={<>
   <button className="button secondary" onClick={()=>downloadReport(id).catch(error=>alert(error.message))}><Download size={18}/>Markdown 다운로드</button>
   {report.status==='DRAFT'&&editable&&<button className="button" disabled={command.isPending||!draft.dirty} onClick={saveDraft}>수정 내용 저장</button>}
  </>}/>
  {report.status==='DRAFT'&&editable&&<ConfirmPanel
   review={draft.review} dirty={draft.dirty} pending={command.isPending} onReviewChange={draft.setReview} onConfirm={confirmReport}
  />}
 </>;
}
