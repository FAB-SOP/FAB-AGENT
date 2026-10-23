import {useState} from 'react';
import {useQueryClient} from '@tanstack/react-query';
import {write} from '../api/client';
import {type Incident,type Obj,when} from '../api/types';
import {Badge,ErrorMessage,Field,Notice} from './ui';

const sourceLabels:Record<string,string>={ACTION:'내 작업 결과',REPLY:'다른 팀 회신',REQUEST_DECISION:'요청 거절 사유'};
/** 결과가 분석에 쓰였는지 — 서버의 analysisUse만 보고 정한다 */
export function resultUse(result:Obj):{code:string;label:string}{
 const use=result.analysisUse||{};
 if(use.activeRequestIds?.length)return {code:'running',label:'추가 분석 진행 중'};
 if(use.successfulCount>0)return {code:'succeeded',label:`분석 반영 ${use.successfulCount}회`};
 return {code:'pending',label:'미반영'};
}
const refKey=(ref:Obj)=>ref.type+':'+ref.id;
/** 실행 중인 분석이 있으면 서버가 409로 막으므로 미리 막고 이유를 보여준다. 승인 대기는 막지 않는다 */
export function followupBlockReason(running?:Obj|null):string{
 return running&&['QUEUED','RUNNING'].includes(running.status)?'분석이 진행 중입니다. 끝난 뒤 추가 분석을 요청할 수 있습니다.':'';
}
/** 내 작업 결과·다른 팀 회신을 모아 보고, 고른 결과로만 추가 분석을 요청한다(자동 재분석 없음) */
export function ResultReview({incident,canManage,results:items,running}:{incident:Incident;canManage:boolean;results:Obj[];running?:Obj|null}){
 const queryClient=useQueryClient();
 const [selected,setSelected]=useState<string[]>([]);
 const [question,setQuestion]=useState('');
 const [state,setState]=useState<{busy:boolean;message:string;error:unknown}>({busy:false,message:'',error:null});
 if(!items.length)return null;
 const unused=items.filter(item=>resultUse(item).code==='pending').length;
 const toggle=(key:string)=>setSelected(keys=>keys.includes(key)?keys.filter(other=>other!==key):[...keys,key]);
 const submit=async()=>{
  setState({busy:true,message:'',error:null});
  try{
   await write(`/api/incidents/${incident.incidentId}/followup-analysis-jobs`,{
    sourceRefs:items.filter(item=>selected.includes(refKey(item.sourceRef))).map(item=>item.sourceRef),
    question:question.trim()||null,
    expectedIncidentVersion:incident.version,
   });
   setSelected([]);setQuestion('');
   setState({busy:false,message:'선택한 결과로 추가 분석을 요청했습니다. 분석이 끝나면 위 조사 내용에 표시됩니다.',error:null});
   queryClient.invalidateQueries({queryKey:['conversation',incident.incidentId]});
  }catch(error){
   setState({busy:false,message:'',error});
  }
 };
 const blockReason=followupBlockReason(running);
 const selectDisabled=!canManage||incident.status==='CLOSED'||state.busy;
 const disabled=selectDisabled||!!blockReason;
 return <section className="result-review">
  <h3>결과 검토 <span className="small muted">미반영 {unused}건 · 추가 분석에 반영할 결과를 고르세요</span></h3>
  {items.map(item=>{
   const key=refKey(item.sourceRef);const use=resultUse(item);
   return <label key={key} className={'result-item'+(selected.includes(key)?' selected':'')}>
    <input type="checkbox" checked={selected.includes(key)} disabled={selectDisabled} onChange={()=>toggle(key)}/>
    <div>
     <div className="result-head">
      <span className="work-route">{sourceLabels[item.sourceRef.type]||item.sourceRef.type}</span>
      <Badge value={use.code}>{use.label}</Badge>
     </div>
     <p>{item.content}</p>
     <span className="small muted">{item.authorUserId} · {when(item.createdAt)} KST</span>
    </div>
   </label>;
  })}
  <Field label="추가로 물어볼 내용 (선택)">
   <textarea value={question} maxLength={2000} disabled={selectDisabled} placeholder="이 결과를 바탕으로 원인 후보와 다음 확인 사항을 갱신해 주세요."
    onChange={event=>setQuestion(event.target.value)}/>
  </Field>
  {blockReason&&<p className="warning-text">{blockReason}</p>}
  {state.message&&<Notice>{state.message}</Notice>}
  {!!state.error&&<ErrorMessage error={state.error}/>}
  <button className="button" disabled={disabled||!selected.length} onClick={submit}>
   {state.busy?'요청 중…':`선택한 결과 ${selected.length}건으로 추가 분석`}
  </button>
  <p className="small muted">결과를 저장하는 것만으로는 분석이 다시 돌지 않습니다. 추가 분석이 필요 없으면 그대로 두면 됩니다.</p>
 </section>;
}
