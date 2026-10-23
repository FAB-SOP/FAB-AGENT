import {useState} from 'react';
import {useSearchParams} from 'react-router-dom';
import {useQuery} from '@tanstack/react-query';
import {ArrowUpRight,Check} from 'lucide-react';
import {api,write} from '../api/client';
import {labels,when,type Incident,type Obj,type Page} from '../api/types';
import {useAuth} from '../auth';
import {Badge,Empty,ErrorMessage,Field,Modal,Notice,PageHeading,Panel} from '../components/ui';
import {PayloadDetails} from '../components/InvestigationContext';
import {AnalysisResultBody} from '../components/InvestigationConversation';
import {WorkManagerApprovals} from '../components/WorkItems';
import {CauseClassificationSelect} from '../components/CauseClassificationSelect';
import {
 describeClassification,emptyClassificationDraft,isClassificationComplete,toClassificationRequest,type ClassificationDraft,
} from '../domain/causeClassification';
import {canManageInvestigation} from '../domain/permissions';
import {ErrorMessageIfFailed,useCommand} from './workflowShared';
/** 저장된 제안 버전 · 해시로 결정한다 — 그 사이 바뀌었으면 서버가 거부한다. 보고서 확정 승인에만 원인 분류를 함께 보낸다 */
function decideApproval(approvalId:string,approval:Obj,decision:string,comment:string,classification:ClassificationDraft){
 const needsClassification=decision==='APPROVE'&&isReportConfirmation(approval);
 return write(`/api/approvals/${approvalId}/decisions`,{
  decision,comment,expectedVersion:approval.version,actionHash:approval.actionHash,
  ...(needsClassification?{classification:toClassificationRequest(classification)}:{}),
 });
}
const isReportConfirmation=(approval:Obj)=>approval.actionType==='CONFIRM_REPORT';
const executionNotice=(approval:Obj)=>approval.executionRef
 ?'승인된 요청이 실행되었습니다. 받은·보낸 요청에서 진행 상태를 확인하세요.'
 :'승인을 기록했습니다. Agent 재개 후 실행 결과를 기다리고 있습니다.';
function ApprovalRow({approval,onOpen}:{approval:Obj;onOpen:()=>void}){
 return <button className="request-row" onClick={onOpen}>
  <div>
   <span className="eyebrow">{approval.processCode} · {approval.source==='AGENT'?'분석에서 제안':'사용자 제안'}</span>
   <h3>{labels[approval.actionType]||approval.actionType}</h3>
   <span className="small muted">{when(approval.createdAt)} · v{approval.version}</span>
  </div>
  <Badge value={approval.status}/>
  <ArrowUpRight/>
 </button>;
}
/** 이 제안의 근거가 된 분석 답 — 승인 상세의 sourceResultId가 있을 때만 */
function SourceAnalysis({approval}:{approval:Obj}){
 const result=useQuery({
  queryKey:['analysis-source',approval.incidentId,approval.sourceResultId],
  queryFn:()=>api<Obj>(`/api/incidents/${encodeURIComponent(approval.incidentId)}/analysis-results?resultId=${encodeURIComponent(approval.sourceResultId)}`),
  enabled:!!approval.sourceResultId,
 });
 if(!approval.sourceResultId)return null;
 return <details className="source-analysis" open>
  <summary>이 제안의 근거 분석 {result.data?.createdAt&&<span className="small muted">· {when(result.data.createdAt)} KST</span>}</summary>
  {result.error?<ErrorMessage error={result.error}/>:result.data?<AnalysisResultBody result={result.data}/>:<p className="small muted">불러오는 중…</p>}
 </details>;
}
function ApprovalReviewModal({approval,canDecide,comment,onCommentChange,classification,onClassificationChange,command,onDecide,onClose}:{
 approval:Obj;
 canDecide:boolean;
 comment:string;
 onCommentChange:(comment:string)=>void;
 classification:ClassificationDraft;
 onClassificationChange:(classification:ClassificationDraft)=>void;
 command:ReturnType<typeof useCommand>;
 onDecide:(decision:string)=>void;
 onClose:()=>void;
}){
 const choosesClassification=canDecide&&isReportConfirmation(approval);
 const incident=useQuery({
  queryKey:['incident',approval.incidentId],
  queryFn:()=>api<Incident>('/api/incidents/'+approval.incidentId),
  enabled:choosesClassification,
 });
 return <Modal title="실행할 제안 검토" onClose={onClose}>
  <Badge value={approval.status}/>
  <p>{approval.rationale}</p>
  <PayloadDetails value={approval.payload}/>
  <SourceAnalysis approval={approval}/>
  {approval.status==='APPROVED'&&<Notice>{executionNotice(approval)}</Notice>}
  {isReportConfirmation(approval)&&approval.decisionInput&&<p>원인 분류: {describeClassification({
   category:approval.decisionInput.category,
   equipmentParameters:approval.decisionInput.equipmentParameters||[],
   classifiedBy:approval.decidedBy,
   classifiedAt:approval.decidedAt,
  })}</p>}
  {choosesClassification&&<CauseClassificationSelect
   value={classification}
   onChange={onClassificationChange}
   processCode={approval.processCode}
   incidentSensorIds={incident.data?.sensorIds||[]}
  />}
  <Field label="검토 의견 (거절 시 필수)"><textarea value={comment} onChange={event=>onCommentChange(event.target.value)}/></Field>
  <p className="small muted">만료 {when(approval.expiresAt)}</p>
  <ErrorMessageIfFailed command={command}/>
  {canDecide&&<div className="form-actions">
   <button className="button secondary" disabled={!comment.trim()||command.isPending} onClick={()=>onDecide('REJECT')}>거절</button>
   <button
    className="button"
    disabled={command.isPending||choosesClassification&&!isClassificationComplete(classification)}
    onClick={()=>onDecide('APPROVE')}
   ><Check size={18}/>검토한 내용 승인</button>
  </div>}
 </Modal>;
}
/** 승인 검토 창의 상태와 결정 — 승인함과 사건 화면이 같이 쓴다 */
function useApprovalReview(selected:string,setSelected:(id:string)=>void){
 const {session}=useAuth();
 const [comment,setComment]=useState('');
 const [classification,setClassification]=useState<ClassificationDraft>(emptyClassificationDraft);
 const command=useCommand();
 const detail=useQuery({queryKey:['approval',selected],queryFn:()=>api<Obj>('/api/approvals/'+selected),enabled:!!selected});
 const decide=(decision:string)=>command.exec(
  ()=>decideApproval(selected,detail.data!,decision,comment,classification),
  ()=>setSelected(''),
 );
 const openApproval=(approvalId:string)=>{
  setSelected(approvalId);
  setComment('');
  setClassification(emptyClassificationDraft);
 };
 const modal=selected&&detail.data?<ApprovalReviewModal
  approval={detail.data}
  canDecide={detail.data.status==='PENDING'&&canManageInvestigation(session,detail.data.processCode)}
  comment={comment}
  onCommentChange={setComment}
  classification={classification}
  onClassificationChange={setClassification}
  command={command}
  onDecide={decide}
  onClose={()=>setSelected('')}
 />:null;
 return {command,openApproval,modal};
}
/** 사건 화면 흐름 카드의 승인 검토 버튼 — 승인함과 같은 검토 창 */
export function ApprovalReviewButton({approvalId,disabled}:{approvalId:string;disabled?:boolean}){
 const [selected,setSelected]=useState('');
 const {command,openApproval,modal}=useApprovalReview(selected,setSelected);
 return <>
  <button className="button" disabled={disabled} onClick={()=>openApproval(approvalId)}>검토하고 승인·거절</button>
  {command.message&&<Notice tone={command.isError?'error':'info'}>{command.message}</Notice>}
  {modal}
 </>;
}
export function Approvals(){
 const [params,setParams]=useSearchParams();
 const selected=params.get('approval')||'';
 const setSelected=(id:string)=>setParams(id?{approval:id}:{},{replace:true});
 const {command,openApproval,modal}=useApprovalReview(selected,setSelected);
 const approvals=useQuery({queryKey:['approvals'],queryFn:()=>api<Page<Obj>>('/api/approvals'),refetchInterval:5000});
 return <>
  <PageHeading kicker="HUMAN REVIEW" title="제안 검토·승인" description="실행할 내용과 근거를 검토합니다."/>
  {command.message&&<Notice tone={command.isError?'error':'info'}>{command.message}</Notice>}
  <WorkManagerApprovals/>
  <Panel title="확인·정비 요청 발송 검토">
   {approvals.isError?<ErrorMessage error={approvals.error}/>:approvals.data?.items.length?approvals.data.items.map(approval=><ApprovalRow
    key={approval.approvalId}
    approval={approval}
    onOpen={()=>openApproval(approval.approvalId)}
   />):<Empty>검토할 제안이 없습니다. 이상 조사에서 추가 질문하거나 확인 요청 제안을 만들 수 있습니다.</Empty>}
  </Panel>
  {modal}
 </>;
}
