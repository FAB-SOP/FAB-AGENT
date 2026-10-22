import {useState} from 'react';
import {useSearchParams} from 'react-router-dom';
import {useQuery} from '@tanstack/react-query';
import {ArrowUpRight,Check} from 'lucide-react';
import {api,write} from '../api/client';
import {labels,when,type Obj,type Page} from '../api/types';
import {useAuth} from '../auth';
import {Badge,Empty,ErrorMessage,Field,Modal,Notice,PageHeading,Panel} from '../components/ui';
import {PayloadDetails} from '../components/InvestigationContext';
import {WorkManagerApprovals} from '../components/WorkItems';
import {canManageInvestigation} from '../domain/permissions';
import {ErrorMessageIfFailed,useCommand} from './workflowShared';
/** 저장된 제안 버전 · 해시로 결정한다 — 그 사이 바뀌었으면 서버가 거부한다 */
function decideApproval(approvalId:string,approval:Obj,decision:string,comment:string){
 return write(`/api/approvals/${approvalId}/decisions`,{decision,comment,expectedVersion:approval.version,actionHash:approval.actionHash});
}
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
function ApprovalReviewModal({approval,canDecide,comment,onCommentChange,command,onDecide,onClose}:{
 approval:Obj;
 canDecide:boolean;
 comment:string;
 onCommentChange:(comment:string)=>void;
 command:ReturnType<typeof useCommand>;
 onDecide:(decision:string)=>void;
 onClose:()=>void;
}){
 return <Modal title="실행할 제안 검토" onClose={onClose}>
  <Badge value={approval.status}/>
  <p>{approval.rationale}</p>
  <PayloadDetails value={approval.payload}/>
  {approval.status==='APPROVED'&&<Notice>{executionNotice(approval)}</Notice>}
  <Field label="검토 의견 (거절 시 필수)"><textarea value={comment} onChange={event=>onCommentChange(event.target.value)}/></Field>
  <p className="small muted">만료 {when(approval.expiresAt)}</p>
  <ErrorMessageIfFailed command={command}/>
  {canDecide&&<div className="form-actions">
   <button className="button secondary" disabled={!comment.trim()||command.isPending} onClick={()=>onDecide('REJECT')}>거절</button>
   <button className="button" disabled={command.isPending} onClick={()=>onDecide('APPROVE')}><Check size={18}/>검토한 내용 승인</button>
  </div>}
 </Modal>;
}
export function Approvals(){
 const {session}=useAuth();
 const [params,setParams]=useSearchParams();
 const selected=params.get('approval')||'';
 const setSelected=(id:string)=>setParams(id?{approval:id}:{},{replace:true});
 const [comment,setComment]=useState('');
 const command=useCommand();
 const approvals=useQuery({queryKey:['approvals'],queryFn:()=>api<Page<Obj>>('/api/approvals'),refetchInterval:5000});
 const detail=useQuery({queryKey:['approval',selected],queryFn:()=>api<Obj>('/api/approvals/'+selected),enabled:!!selected});
 const decide=(decision:string)=>command.exec(()=>decideApproval(selected,detail.data!,decision,comment),()=>setSelected(''));
 const openApproval=(approvalId:string)=>{
  setSelected(approvalId);
  setComment('');
 };
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
  {selected&&detail.data&&<ApprovalReviewModal
   approval={detail.data}
   canDecide={detail.data.status==='PENDING'&&canManageInvestigation(session,detail.data.processCode)}
   comment={comment}
   onCommentChange={setComment}
   command={command}
   onDecide={decide}
   onClose={()=>setSelected('')}
  />}
 </>;
}
