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
/** 받은 요청에서 할 수 있는 일 — 서버의 allowedActions를 그대로 쓴다. 필드가 없던 이전 서버는 담당자·접수 상태면 회신만 */
export function requestActions(request:Obj,userId?:string):string[]{
 if(Array.isArray(request.allowedActions))return request.allowedActions;
 return request.ownerUserId===userId&&request.status==='OPEN'?['REPLY']:[];
}
type DecisionForm={reason:string;onReasonChange:(reason:string)=>void;onDecide:(decision:string)=>void};
/** 수락하면 회신을 쓸 수 있고, 거절은 사유가 필요하다 */
function RequestDecision({form,pending}:{form:DecisionForm;pending:boolean}){
 return <div className="request-decision">
  <Notice>이 요청을 수락하면 확인 후 결과를 회신할 수 있습니다. 수락·거절만으로 조사가 진행되거나 종료되지 않습니다.</Notice>
  <Field label="거절 사유 (거절 시 필수)">
   <textarea value={form.reason} maxLength={2000} disabled={pending} onChange={event=>form.onReasonChange(event.target.value)}/>
  </Field>
  <div className="work-buttons">
   <button type="button" className="button" disabled={pending} onClick={()=>form.onDecide('ACCEPT')}>요청 수락</button>
   <button type="button" className="button secondary" disabled={pending||!form.reason.trim()} onClick={()=>form.onDecide('REJECT')}>요청 거절</button>
  </div>
  {!form.reason.trim()&&<p className="small muted">거절하려면 사유를 입력하세요.</p>}
 </div>;
}
type ReplyForm={
 findings:string;
 confirmed:boolean;
 onFindingsChange:(findings:string)=>void;
 onConfirmedChange:(confirmed:boolean)=>void;
 onSubmit:()=>void;
};
function RequestDetailModal({request,actions,decision,reply,command,onClose}:{
 request:Obj;actions:string[];decision:DecisionForm;reply:ReplyForm;command:ReturnType<typeof useCommand>;onClose:()=>void;
}){
 const canReply=actions.includes('REPLY');
 return <Modal title="확인·정비 요청 상세" onClose={onClose}>
  <Badge value={request.status}/>
  <h3>{request.question}</h3>
  <p className="small muted">{request.incident?.title} · {request.incident?.lotId}</p>
  <RequestContext value={request}/>
  {request.replies?.map((item:Obj)=><div className="record" key={item.replyId}>
   <p>{item.findings}</p>
   <span className="small muted">{when(item.createdAt)} · {item.authorUserId}</span>
  </div>)}
  {request.status==='REJECTED'&&request.decisionComment&&<div className="record">
   <strong>거절 사유</strong><p>{request.decisionComment}</p>
   {request.decisionAt&&<span className="small muted">{when(request.decisionAt)}</span>}
  </div>}
  {actions.includes('ACCEPT')&&<><RequestDecision form={decision} pending={command.isPending}/><ErrorMessageIfFailed command={command}/></>}
  {canReply&&<form onSubmit={event=>{event.preventDefault();reply.onSubmit()}}>
   <Field label="확인한 내용과 결과"><textarea required value={reply.findings} onChange={event=>reply.onFindingsChange(event.target.value)}/></Field>
   <label className="check">
    <input type="checkbox" required checked={reply.confirmed} onChange={event=>reply.onConfirmedChange(event.target.checked)}/>직접 확인한 내용입니다.
   </label>
   <ErrorMessageIfFailed command={command}/>
   <button className="button full" disabled={command.isPending}>회신 등록</button>
  </form>}
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
 const [reason,setReason]=useState('');
 const command=useCommand();
 const {requests,detail}=useRequestQueries(box,selected);
 const openRequest=(requestId:string)=>{
  setSelected(requestId);
  setFindings('');
  setConfirmed(false);
  setReason('');
 };
 /** 수락하면 창을 그대로 두어 바로 회신을 쓰게 하고, 거절하면 닫는다 */
 const decide=(decision:string)=>command.exec(
  ()=>write(`/api/check-requests/${selected}/decisions`,{decision,comment:decision==='REJECT'?reason:null,expectedVersion:detail.data!.version}),
  decision==='REJECT'?()=>setSelected(''):undefined,
  decision==='REJECT'?'요청을 거절했습니다.':'요청을 수락했습니다. 확인 후 결과를 회신하세요.',
 );
 const submitReply=()=>command.exec(
  ()=>write(`/api/check-requests/${selected}/replies`,{findings,confirmedByUser:confirmed,expectedVersion:detail.data!.version,evidenceIds:[]}),
  ()=>setSelected(''),
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
   actions={requestActions(detail.data,session?.user.userId)}
   decision={{reason,onReasonChange:setReason,onDecide:decide}}
   reply={{findings,confirmed,onFindingsChange:setFindings,onConfirmedChange:setConfirmed,onSubmit:submitReply}}
   command={command}
   onClose={()=>setSelected('')}
  />}
 </>;
}
