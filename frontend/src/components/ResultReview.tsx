import {useState} from 'react';
import {useQueryClient} from '@tanstack/react-query';
import {write} from '../api/client';
import {type Incident,type Obj} from '../api/types';
import {ErrorMessage,Field,Notice} from './ui';

export const refKey=(ref:Obj)=>ref.type+':'+ref.id;
/** 실행 중인 분석이 있으면 서버가 409로 막으므로 미리 막고 이유를 보여준다. 승인 대기는 막지 않는다 */
export function followupBlockReason(running?:Obj|null):string{
 return running&&['QUEUED','RUNNING'].includes(running.status)?'분석이 진행 중입니다. 끝난 뒤 추가 분석을 요청할 수 있습니다.':'';
}
/** 카드에서 고른 회신·작업 결과로 추가 분석 — 원문은 카드에 있고 여기는 개수·참조 제목·질문·버튼만(자동 재분석 없음) */
export function SelectionBar({incident,picked,running,onClear}:{incident:Incident;picked:Obj[];running?:Obj|null;onClear:()=>void}){
 const queryClient=useQueryClient();
 const [question,setQuestion]=useState('');
 const [state,setState]=useState<{busy:boolean;message:string;error:unknown}>({busy:false,message:'',error:null});
 if(!picked.length&&!state.message)return null;
 const submit=async()=>{
  setState({busy:true,message:'',error:null});
  try{
   await write(`/api/incidents/${incident.incidentId}/followup-analysis-jobs`,{
    sourceRefs:picked.map(item=>item.sourceRef),
    question:question.trim()||null,
    expectedIncidentVersion:incident.version,
   });
   onClear();setQuestion('');
   setState({busy:false,message:'선택한 결과로 추가 분석을 요청했습니다. 아래에 새 블록으로 표시됩니다.',error:null});
   queryClient.invalidateQueries({queryKey:['conversation',incident.incidentId]});
  }catch(error){
   setState({busy:false,message:'',error});
  }
 };
 const blockReason=followupBlockReason(running);
 return <section className="selection-bar">
  {state.message&&<Notice>{state.message}</Notice>}
  {picked.length>0&&<>
   <strong>추가 분석에 선택한 결과 {picked.length}건</strong>
   <ul className="small muted">{picked.map(item=><li key={item.entryId}>
    {item.type==='REPLY'?`${item.actor?.displayName} 님의 회신`:`${item.actor?.displayName} 님의 작업 결과`}
    {item.requestSummary?.question&&` · ${item.requestSummary.question}`}
   </li>)}</ul>
   <Field label="추가로 물어볼 내용 (선택)">
    <textarea value={question} maxLength={2000} disabled={state.busy} placeholder="이 결과를 바탕으로 원인 후보와 다음 확인 사항을 갱신해 주세요."
     onChange={event=>setQuestion(event.target.value)}/>
   </Field>
   {blockReason&&<p className="warning-text">{blockReason}</p>}
   <div className="form-actions">
    <button type="button" className="button secondary" disabled={state.busy} onClick={onClear}>선택 해제</button>
    <button type="button" className="button" disabled={state.busy||!!blockReason} onClick={submit}>{state.busy?'요청 중…':`선택한 결과 ${picked.length}건으로 추가 분석`}</button>
   </div>
  </>}
  {!!state.error&&<ErrorMessage error={state.error}/>}
 </section>;
}
