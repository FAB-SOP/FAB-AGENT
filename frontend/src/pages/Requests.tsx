import {useState} from 'react';
import {useSearchParams} from 'react-router-dom';
import {useQuery} from '@tanstack/react-query';
import {ArrowUpRight} from 'lucide-react';
import {api,write} from '../api/client';
import {when,type Obj,type Page} from '../api/types';
import {useAuth} from '../auth';
import {Badge,Empty,ErrorMessage,Field,Modal,Notice,PageHeading,Panel} from '../components/ui';
import {RequestContext} from '../components/InvestigationContext';
import {ErrorMessageIfFailed,useCommand} from './workflowShared';
const REQUEST_BOXES=[['received','받은 요청'],['sent','보낸 요청']];
const emptyMessageFor=(box:string)=>box==='received'
 ?'받은 확인 요청이 없습니다.'
 :'보낸 확인 요청이 없습니다. 사건 상세에서 제안을 만들고 승인하면 등록됩니다.';
function BoxTabs({box,onChange}:{box:string;onChange:(box:string)=>void}){
 return <div className="tabs">
  {REQUEST_BOXES.map(([value,title])=><button key={value} className={box===value?'active':''} onClick={()=>onChange(value)}>{title}</button>)}
 </div>;
}
function RequestRow({request,onOpen}:{request:Obj;onOpen:()=>void}){
 return <button className="request-row" onClick={onOpen}>
  <div>
   <span className="eyebrow">{request.incidentDisplayId} → {request.targetProcessCode}</span>
   <h3>{request.question}</h3>
   <span className="small muted">{when(request.createdAt)} · 담당 {request.ownerUserId}</span>
  </div>
  <Badge value={request.status}/>
  <ArrowUpRight/>
 </button>;
}
type ReplyForm={
 findings:string;
 confirmed:boolean;
 onFindingsChange:(findings:string)=>void;
 onConfirmedChange:(confirmed:boolean)=>void;
 onSubmit:()=>void;
};
type WithdrawForm={reason:string;onReasonChange:(reason:string)=>void;onSubmit:()=>void};
/** 요청자는 회신이 오지 않은 요청(대기 · 수락됨)을 사유와 함께 철회할 수 있다 — 보고서 확정 전 정리 */
function WithdrawSection({request,canWithdraw,withdraw,command}:{
 request:Obj;canWithdraw:boolean;withdraw:WithdrawForm;command:ReturnType<typeof useCommand>;
}){
 if(request.status==='CANCELLED'&&request.withdrawReason)return <div className="record">
  <p>철회됨: {request.withdrawReason}</p>
  <span className="small muted">{when(request.withdrawnAt)} · {request.withdrawnBy}</span>
 </div>;
 if(!canWithdraw)return null;
 return <form onSubmit={event=>{event.preventDefault();withdraw.onSubmit()}}>
  <Field label="철회 사유"><textarea required maxLength={500} value={withdraw.reason} onChange={event=>withdraw.onReasonChange(event.target.value)}/></Field>
  <button className="button secondary full" disabled={command.isPending||!withdraw.reason.trim()}>요청 철회</button>
 </form>;
}
function RequestDetailModal({request,canReply,canWithdraw,reply,withdraw,command,onClose}:{
 request:Obj;canReply:boolean;canWithdraw:boolean;reply:ReplyForm;withdraw:WithdrawForm;command:ReturnType<typeof useCommand>;onClose:()=>void;
}){
 return <Modal title="확인·정비 요청 상세" onClose={onClose}>
  <Badge value={request.status}/>
  <h3>{request.question}</h3>
  <p className="small muted">{request.incident?.title} · {request.incident?.lotId}</p>
  <RequestContext value={request}/>
  {request.replies?.map((item:Obj)=><div className="record" key={item.replyId}>
   <p>{item.findings}</p>
   <span className="small muted">{when(item.createdAt)} · {item.authorUserId}</span>
  </div>)}
  {canReply&&<form onSubmit={event=>{event.preventDefault();reply.onSubmit()}}>
   <Field label="확인한 내용과 결과"><textarea required value={reply.findings} onChange={event=>reply.onFindingsChange(event.target.value)}/></Field>
   <label className="check">
    <input type="checkbox" required checked={reply.confirmed} onChange={event=>reply.onConfirmedChange(event.target.checked)}/>직접 확인한 내용입니다.
   </label>
   <ErrorMessageIfFailed command={command}/>
   <button className="button full" disabled={command.isPending}>회신 등록 · 조사 재개</button>
  </form>}
  <WithdrawSection request={request} canWithdraw={canWithdraw} withdraw={withdraw} command={command}/>
 </Modal>;
}
/** 받은 · 보낸 요청 목록과 선택한 요청의 상세 */
function useRequestQueries(box:string,selected:string){
 const requests=useQuery({queryKey:['requests',box],queryFn:()=>api<Page<Obj>>('/api/check-requests?box='+box),refetchInterval:5000});
 const detail=useQuery({
  queryKey:['request',selected],
  queryFn:()=>api<Obj>('/api/check-requests/'+selected),
  enabled:!!selected,
  refetchInterval:3000,
 });
 return {requests,detail};
}
export function Requests(){
 const {session}=useAuth();
 const [params,setParams]=useSearchParams();
 const [box,setBox]=useState('received');
 const selected=params.get('request')||'';
 const setSelected=(value:string)=>setParams(value?{request:value}:{});
 const [findings,setFindings]=useState('');
 const [confirmed,setConfirmed]=useState(false);
 const [withdrawReason,setWithdrawReason]=useState('');
 const command=useCommand();
 const {requests,detail}=useRequestQueries(box,selected);
 const openRequest=(requestId:string)=>{
  setSelected(requestId);
  setFindings('');
  setConfirmed(false);
  setWithdrawReason('');
 };
 const submitReply=()=>command.exec(
  ()=>write(`/api/check-requests/${selected}/replies`,{findings,confirmedByUser:confirmed,expectedVersion:detail.data!.version,evidenceIds:[]}),
  ()=>setSelected(''),
 );
 const submitWithdraw=()=>command.exec(
  ()=>write(`/api/check-requests/${selected}/withdrawals`,{reason:withdrawReason.trim(),expectedVersion:detail.data!.version}),
  ()=>setWithdrawReason(''),
  '요청을 철회했습니다.',
 );
 return <>
  <PageHeading kicker="CROSS-PROCESS COLLABORATION" title="확인·정비 요청" description="다른 공정과 설비 정비 담당자의 확인·회신을 같은 조사에 연결합니다."/>
  <BoxTabs box={box} onChange={setBox}/>
  {command.message&&<Notice tone={command.isError?'error':'info'}>{command.message}</Notice>}
  <Panel>
   {requests.isError?<ErrorMessage error={requests.error}/>:requests.data?.items.length?requests.data.items.map(request=><RequestRow
    key={request.requestId}
    request={request}
    onOpen={()=>openRequest(request.requestId)}
   />):<Empty>{emptyMessageFor(box)}</Empty>}
  </Panel>
  {detail.data&&selected&&<RequestDetailModal
   request={detail.data}
   canReply={detail.data.ownerUserId===session?.user.userId&&detail.data.status==='OPEN'}
   canWithdraw={detail.data.requesterUserId===session?.user.userId&&['OPEN','ACCEPTED'].includes(detail.data.status)}
   reply={{findings,confirmed,onFindingsChange:setFindings,onConfirmedChange:setConfirmed,onSubmit:submitReply}}
   withdraw={{reason:withdrawReason,onReasonChange:setWithdrawReason,onSubmit:submitWithdraw}}
   command={command}
   onClose={()=>setSelected('')}
  />}
 </>;
}
