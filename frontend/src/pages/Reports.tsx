import {useEffect,useState,type ReactNode} from 'react';
import {Link,useParams} from 'react-router-dom';
import {useQueries,useQuery} from '@tanstack/react-query';
import {ArrowUpRight,FileText,ChevronLeft,Download} from 'lucide-react';
import {api,downloadReport,write} from '../api/client';
import {when,type Incident,type Obj,type Page} from '../api/types';
import {useAuth} from '../auth';
import {Badge,Empty,ErrorMessage,Field,Notice,PageHeading,Panel} from '../components/ui';
import {CauseClassificationSelect,useEquipmentOptions} from '../components/CauseClassificationSelect';
import {
 causeCategories,causeCategoryLabels,describeClassification,emptyClassificationDraft,isClassificationComplete,
 toClassificationRequest,type ClassificationDraft,
} from '../domain/causeClassification';
import {
 emptyReportFilter,filterReports,isValidDetectedRange,type ReportFilter,type ReportRow,
} from '../domain/reportFilters';
import {canManageInvestigation} from '../domain/permissions';
import {useCommand} from './workflowShared';
/** 분류 · 장비 파라미터 · 이상 발생 기간 필터 — 설비 엔지니어와 공정 관리자가 각자 볼 보고서를 고른다 */
function ReportFilters({process,filter,onChange}:{process:string;filter:ReportFilter;onChange:(filter:ReportFilter)=>void}){
 const equipment=useEquipmentOptions(filter.category==='MACHINE'?process:'');
 return <>
  <div className="filter-row">
   <Field label="원인 분류">
    <select value={filter.category} onChange={event=>onChange({
     ...filter,category:event.target.value as ReportFilter['category'],equipmentParameter:'',
    })}>
     <option value="ALL">전체</option>
     {causeCategories.map(category=><option key={category} value={category}>{causeCategoryLabels[category]}</option>)}
     <option value="UNCLASSIFIED">분류 없음</option>
    </select>
   </Field>
   {filter.category==='MACHINE'&&<Field label="장비 파라미터">
    <select value={filter.equipmentParameter} onChange={event=>onChange({...filter,equipmentParameter:event.target.value})}>
     <option value="">전체</option>
     {equipment.options.map(option=><option key={option.sensorId} value={option.sensorId}>{option.label}</option>)}
    </select>
   </Field>}
   <Field label="이상 발생 기간 (시작)">
    <input type="date" value={filter.detectedFrom} onChange={event=>onChange({...filter,detectedFrom:event.target.value})}/>
   </Field>
   <Field label="이상 발생 기간 (종료)">
    <input type="date" value={filter.detectedTo} onChange={event=>onChange({...filter,detectedTo:event.target.value})}/>
   </Field>
  </div>
  {!isValidDetectedRange(filter)&&<p className="warning-text">시작일이 종료일보다 늦어 기간 필터를 적용하지 않았습니다.</p>}
 </>;
}
export function ReportList({process}:{process:string}){
 const [filter,setFilter]=useState<ReportFilter>(emptyReportFilter);
 const incidents=useQuery({
  queryKey:['incidents',process],
  queryFn:()=>api<Page<Incident>>('/api/incidents?processCode='+process+'&size=100'),
 });
 const incidentItems=incidents.data?.items||[];
 const reportQueries=useQueries({queries:incidentItems.map(incident=>({
  queryKey:['reports',incident.incidentId],
  queryFn:()=>api<Obj>(`/api/incidents/${incident.incidentId}/reports`),
 }))});
 const rows:ReportRow[]=reportQueries.flatMap((query,index)=>(query.data?.items||[]).map((report:Obj)=>({
  report,
  detectedAt:incidentItems[index]?.detectedAt,
 })));
 const visibleRows=filterReports(rows,filter);
 return <>
  <PageHeading kicker="REPORTS & RECORDS" title="조사 보고서" description="사건별 초안을 검토·수정하고, 확정된 버전을 보존합니다."/>
  <Panel>
   <ReportFilters process={process} filter={filter} onChange={setFilter}/>
   {visibleRows.length?visibleRows.map(({report,detectedAt})=><Link className="report-row" to={'/reports/'+report.reportId} key={report.reportId}>
    <FileText/>
    <div>
     <strong>{report.title}</strong>
     <span className="table-sub">
      v{report.version} · {when(report.createdAt)} · 이상 발생 {when(detectedAt)} · {describeClassification(report.classification)}
     </span>
    </div>
    <Badge value={report.status}/>
    <ArrowUpRight/>
   </Link>):rows.length
    ?<Empty>조건에 맞는 보고서가 없습니다. 필터를 바꿔 보세요.</Empty>
    :<Empty>아직 보고서가 없습니다. 이상 조사 → 보고서에서 초안 생성을 요청해 주세요.</Empty>}
  </Panel>
 </>;
}
/** 불러온 보고서를 편집 상태로 옮기고, 판본 · 상태가 바뀌면 다시 불러온 내용으로 되돌린다 */
function useReportDraft(report:Obj|undefined){
 const [title,setTitle]=useState('');
 const [sections,setSections]=useState<Obj[]>([]);
 const [review,setReview]=useState(false);
 const [classification,setClassification]=useState<ClassificationDraft>(emptyClassificationDraft);
 const [dirty,setDirty]=useState(false);
 useEffect(()=>{
  if(!report)return;
  setTitle(report.title);
  setSections(report.sections);
  setReview(false);
  setClassification(emptyClassificationDraft);
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
 return {title,sections,review,setReview,classification,setClassification,dirty,editTitle,editSection};
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
function ConfirmPanel({review,classification,processCode,incidentSensorIds,dirty,pending,onReviewChange,onClassificationChange,onConfirm}:{
 review:boolean;
 classification:ClassificationDraft;
 processCode:string;
 incidentSensorIds:string[];
 dirty:boolean;
 pending:boolean;
 onReviewChange:(review:boolean)=>void;
 onClassificationChange:(classification:ClassificationDraft)=>void;
 onConfirm:()=>void;
}){
 const classified=isClassificationComplete(classification);
 return <Panel title="검토 후 확정">
  <label className="check">
   <input type="checkbox" checked={review} onChange={event=>onReviewChange(event.target.checked)}/>보고서 내용을 검토했습니다.
  </label>
  <CauseClassificationSelect
   value={classification}
   onChange={onClassificationChange}
   processCode={processCode}
   incidentSensorIds={incidentSensorIds}
  />
  {!classification.category&&<p className="warning-text">원인 분류를 선택해 주세요.</p>}
  {classification.category&&!classified&&<p className="warning-text">장비 파라미터를 하나 이상 선택해 주세요.</p>}
  {dirty&&<p className="warning-text">먼저 수정 내용을 저장해 주세요.</p>}
  <button className="button" disabled={!review||!classified||dirty||pending} onClick={onConfirm}>검토한 버전 확정</button>
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
  command.exec(()=>write(`/api/reports/${id}/confirmations`,{
   expectedContentRevision:report.contentRevision,
   reviewConfirmed:true,
   classification:toClassificationRequest(draft.classification),
  }));
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
  {report.status==='CONFIRMED'&&<Panel title="원인 분류">
   <p>{describeClassification(report.classification)}</p>
  </Panel>}
  {report.status==='DRAFT'&&editable&&<ConfirmPanel
   review={draft.review}
   classification={draft.classification}
   processCode={reportIncident.data?.processCode||report.processCode||''}
   incidentSensorIds={reportIncident.data?.sensorIds||[]}
   dirty={draft.dirty}
   pending={command.isPending}
   onReviewChange={draft.setReview}
   onClassificationChange={draft.setClassification}
   onConfirm={confirmReport}
  />}
 </>;
}
