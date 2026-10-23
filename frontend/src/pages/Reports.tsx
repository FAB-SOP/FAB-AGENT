import {useEffect,useState,type ReactNode} from 'react';
import {Link,useParams} from 'react-router-dom';
import {keepPreviousData,useInfiniteQuery,useQuery} from '@tanstack/react-query';
import {ArrowUpRight,FileText,ChevronLeft,Download} from 'lucide-react';
import {api,downloadReport,write} from '../api/client';
import {searchReports,type ReportSlice} from '../api/reports';
import {when,type Incident,type Obj} from '../api/types';
import {useAuth} from '../auth';
import {Badge,Empty,ErrorMessage,Field,Notice,PageHeading,Panel} from '../components/ui';
import {CauseClassificationSelect,useEquipmentOptions} from '../components/CauseClassificationSelect';
import {
 causeCategories,causeCategoryLabels,describeClassification,emptyClassificationDraft,isClassificationComplete,
 toClassificationRequest,type ClassificationDraft,
} from '../domain/causeClassification';
import {emptyReportFilter,isValidDetectedRange,type ReportFilter} from '../domain/reportFilters';
import {toSearchParams} from '../domain/reportSearch';
import {useDebouncedValue} from '../hooks/useDebouncedValue';
import {canManageInvestigation} from '../domain/permissions';
import {
 blockersFrom,canRetrySummary,describeBlocker,isSummaryInProgress,summaryGeneratorLabels,summaryStatusLabels,
 type ConfirmationBlocker,type ReportAiSummary,
} from '../domain/reportConfirmation';
import {useCommand} from './workflowShared';
/** 제목 검색 · 분류 · 장비 파라미터 · 이상 발생 기간 필터 — 설비 엔지니어와 공정 관리자가 각자 볼 보고서를 고른다 */
function ReportFilters({process,keyword,filter,onKeywordChange,onKeywordSubmit,onChange}:{
 process:string;
 keyword:string;
 filter:ReportFilter;
 onKeywordChange:(keyword:string)=>void;
 onKeywordSubmit:()=>void;
 onChange:(filter:ReportFilter)=>void;
}){
 const equipment=useEquipmentOptions(filter.category==='MACHINE'?process:'');
 return <>
  <div className="filter-row report-filters">
   <Field label="제목 검색">
    <input
     type="search"
     value={keyword}
     maxLength={REPORT_KEYWORD_MAX_LENGTH}
     placeholder="사건 번호 · 설비 · 현상"
     onChange={event=>onKeywordChange(event.target.value)}
     onKeyDown={event=>{if(event.key==='Enter')onKeywordSubmit();}}
    />
   </Field>
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
const REPORT_KEYWORD_MAX_LENGTH=100;
/** 보고서 목록 — 서버가 공정 · 검색어 · 필터를 한 번에 조회하고, 「더 보기」로 다음 페이지를 이어 붙인다 */
export function ReportList({process}:{process:string}){
 const [filter,setFilter]=useState<ReportFilter>(emptyReportFilter);
 const [keyword,setKeyword]=useState('');
 const searchKeyword=useDebouncedValue(keyword);
 const params=toSearchParams(process,searchKeyword.debounced,filter);
 const reports=useInfiniteQuery({
  queryKey:['reports',params],
  queryFn:({pageParam})=>searchReports(params,pageParam),
  initialPageParam:0,
  getNextPageParam:(lastPage:ReportSlice)=>lastPage.hasNext?lastPage.page+1:undefined,
  placeholderData:keepPreviousData,
 });
 const rows=reports.data?.pages.flatMap(page=>page.items)||[];
 const searching=!!params.q||Object.keys(params).length>1;
 return <>
  <PageHeading kicker="REPORTS & RECORDS" title="조사 보고서" description="사건별 초안을 검토·수정하고, 확정된 버전을 보존합니다."/>
  <Panel>
   <ReportFilters
    process={process}
    keyword={keyword}
    filter={filter}
    onKeywordChange={setKeyword}
    onKeywordSubmit={searchKeyword.flush}
    onChange={setFilter}
   />
   {reports.isError&&<ErrorMessage error={reports.error} retry={()=>reports.refetch()}/>}
   {rows.length?rows.map(report=><Link className="report-row" to={'/reports/'+report.reportId} key={report.reportId}>
    <FileText/>
    <div>
     <strong>{report.title}</strong>
     <span className="table-sub">
      v{report.version} · {when(report.createdAt)} · 이상 발생 {when(report.detectedAt)} · {describeClassification(report.classification)}
     </span>
    </div>
    <Badge value={report.status}/>
    <ArrowUpRight/>
   </Link>):reports.isPending
    ?<Empty>보고서를 불러오는 중…</Empty>
    :params.q
     ?<Empty>검색 결과가 없습니다. 검색어나 필터를 바꿔 보세요.</Empty>
     :searching
      ?<Empty>조건에 맞는 보고서가 없습니다. 필터를 바꿔 보세요.</Empty>
      :<Empty>아직 보고서가 없습니다. 이상 조사 → 보고서에서 초안 생성을 요청해 주세요.</Empty>}
   {reports.hasNextPage&&<div className="form-actions">
    <button className="button secondary" disabled={reports.isFetchingNextPage} onClick={()=>reports.fetchNextPage()}>
     {reports.isFetchingNextPage?'불러오는 중…':'더 보기'}
    </button>
   </div>}
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
/** 확정 전 정리 목록 — 서버가 확정을 막은 남은 일과 정리 방법 */
function ConfirmationBlockers({blockers,incidentId}:{blockers:ConfirmationBlocker[];incidentId:string}){
 return <Panel title="확정 전 정리 목록">
  <p className="warning-text">아래 일이 남아 있어 확정할 수 없습니다. 정리한 뒤 다시 확정해 주세요. 시스템이 자동으로 취소 · 완료하지 않습니다.</p>
  <ul className="blocker-list">
   {blockers.map(blocker=>{
    const view=describeBlocker(blocker,incidentId);
    return <li key={blocker.kind+blocker.id}>
     <strong>{view.title}</strong> — {view.label}
     <p>{view.howTo} <Link to={view.path}>바로 가기<ArrowUpRight size={14}/></Link></p>
    </li>;
   })}
  </ul>
 </Panel>;
}
/** 확정본의 AI 요약 — 대기 · 진행 · 완료(1~2줄 · 근거 · 생성 방식) · 실패(다시 요청) */
function SummaryPanel({summary,canRetry,pending,onRetry}:{
 summary:ReportAiSummary|null|undefined;
 canRetry:boolean;
 pending:boolean;
 onRetry:()=>void;
}){
 if(!summary)return <Panel title="AI 요약"><p>이 확정본에는 요약이 없습니다.</p></Panel>;
 return <Panel title="AI 요약" extra={<span className="summary-status">{summaryStatusLabels[summary.status]}</span>}>
  {summary.status==='READY'&&<>
   {summary.lines.map(line=><p key={line} className="summary-line">{line}</p>)}
   <p className="muted">생성 방식: {summaryGeneratorLabels[summary.generatedBy||'']||summary.generatedBy}
    {summary.evidenceIds.length>0&&<> · 근거 {summary.evidenceIds.join(', ')}</>}</p>
  </>}
  {isSummaryInProgress(summary)&&<p className="muted">확정본을 요약하고 있습니다. 잠시 후 자동으로 갱신됩니다.</p>}
  {summary.status==='FAILED'&&<>
   <p className="warning-text">{summary.error||'요약을 만들지 못했습니다.'}</p>
   {canRetry&&<button className="button secondary" disabled={pending} onClick={onRetry}>요약 다시 요청</button>}
  </>}
 </Panel>;
}
export function ReportEditor(){
 const {session}=useAuth();
 const {id=''}=useParams();
 const reportQuery=useQuery({
  queryKey:['report',id],
  queryFn:()=>api<Obj>('/api/reports/'+id),
  // 요약이 대기 · 진행 중이면 짧은 주기로 다시 읽는다
  refetchInterval:query=>isSummaryInProgress(query.state.data?.summary)?2000:false,
 });
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
 const canManage=canManageInvestigation(session,reportIncident.data?.processCode||'');
 const incidentClosed=reportIncident.data?.status==='CLOSED';
 const editable=canManage&&!incidentClosed;
 const locked=!editable||report.status==='CONFIRMED';
 const blockers=blockersFrom(command.error);
 const saveDraft=()=>command.exec(()=>saveReportDraft(id,draft.title,draft.sections,report.contentRevision));
 const confirmReport=()=>{
  if(!confirm('이 버전의 보고서를 확정하시겠습니까?'))return;
  command.exec(()=>write(`/api/reports/${id}/confirmations`,{
   expectedContentRevision:report.contentRevision,
   reviewConfirmed:true,
   classification:toClassificationRequest(draft.classification),
  }),undefined,'보고서를 확정하고 사건을 종료했습니다. 확정본 요약을 시작합니다.');
 };
 const retrySummary=()=>command.exec(()=>write(`/api/reports/${id}/summary-jobs`,{}),undefined,'요약을 다시 요청했습니다.');
 return <>
  <Link className="back-link" to={'/incidents/'+report.incidentId}><ChevronLeft size={17}/>사건으로 돌아가기</Link>
  <PageHeading
   kicker={`REPORT VERSION ${report.version} · REVISION ${report.contentRevision}`}
   title="조사 보고서 검토"
   description="내용을 검토·수정한 뒤 확정합니다."
   extra={<Badge value={report.status}/>}
  />
  {command.message&&<Notice tone={command.isError?'error':'info'}>{command.message}</Notice>}
  {blockers.length>0&&<ConfirmationBlockers blockers={blockers} incidentId={report.incidentId}/>}
  {incidentClosed&&report.status==='DRAFT'&&<Notice tone="info">종료된 사건의 초안은 수정 · 확정할 수 없습니다. 사건은 보고서 확정으로 종료되었습니다.</Notice>}
  <ReportForm title={draft.title} sections={draft.sections} locked={locked} onTitleChange={draft.editTitle} onSectionChange={draft.editSection} actions={<>
   <button className="button secondary" onClick={()=>downloadReport(id).catch(error=>alert(error.message))}><Download size={18}/>Markdown 다운로드</button>
   {report.status==='DRAFT'&&editable&&<button className="button" disabled={command.isPending||!draft.dirty} onClick={saveDraft}>수정 내용 저장</button>}
  </>}/>
  {report.status==='CONFIRMED'&&<Panel title="원인 분류">
   <p>{describeClassification(report.classification)}</p>
  </Panel>}
  {report.status==='CONFIRMED'&&<SummaryPanel
   summary={report.summary}
   canRetry={canRetrySummary(report.summary,canManage)}
   pending={command.isPending}
   onRetry={retrySummary}
  />}
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
