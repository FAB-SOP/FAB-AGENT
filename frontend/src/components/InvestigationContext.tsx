import {fmt,type Obj} from '../api/types';
const fieldLabels:Record<string,string>={
 ownerUserId:'담당자',
 targetProcessCode:'대상 공정',
 question:'확인할 내용',
 reportId:'보고서',
 description:'수행 내용',
 equipmentId:'대상 설비',
 causeIds:'원인 후보 ID',
 expectedContentRevision:'검토한 내용 버전',
};
const metricLabels:Record<string,string>={
 depositedA:'증착량',
 removedA:'제거량',
 outputMeanA:'공정 후 평균 두께',
 inputMeanA:'입고 평균 두께',
};
function DetailValue({value}:{value:unknown}){
 if(value==null)return <>—</>;
 if(Array.isArray(value))return <>{value.map((item,index)=><div key={index}><DetailValue value={item}/></div>)}</>;
 if(typeof value==='object')return <dl className="facts">
  {Object.entries(value).map(([key,item])=><div key={key}>
   <dt>{fieldLabels[key]||key}</dt>
   <dd><DetailValue value={item}/></dd>
  </div>)}
 </dl>;
 return <>{String(value)}</>;
}
/** 편차 기준 문구 — 관리한계(CONTROL_LIMIT)는 정상 평균·σ가 아니라 관리 중심값·관리한계로 보여준다 */
export function deviationBasis(deviation:Obj):string{
 if(deviation.thresholdKind==='CONTROL_LIMIT'){
  const limits=deviation.lcl!=null&&deviation.ucl!=null?` · 관리한계 ${fmt(deviation.lcl,1)}~${fmt(deviation.ucl,1)}`:'';
  return (deviation.baselineMean==null?'—':`관리 중심 ${fmt(deviation.baselineMean,1)}`)+limits;
 }
 if(deviation.baselineMean==null)return '—';
 return `정상 평균 ${fmt(deviation.baselineMean,3)}`+(deviation.z!=null?` / ${fmt(deviation.z,2)}σ`:'');
}
function DeviationTable({deviations}:{deviations:Obj[]}){
 return <div className="table-wrap">
  <table>
   <thead>
    <tr>
     <th>설비·지표</th>
     <th>관측값</th>
     <th>기준·편차</th>
    </tr>
   </thead>
   <tbody>
    {deviations.map((deviation:Obj,index:number)=><tr key={index}>
     <td>{deviation.equipmentId} · {metricLabels[deviation.metric]||deviation.metric}<span className="table-sub">{deviation.runId}</span></td>
     <td>{typeof deviation.value==='number'?fmt(deviation.value,deviation.unit==='Å'?1:3):String(deviation.value??'—')} {deviation.unit}</td>
     <td>
      {deviationBasis(deviation)}
      {deviation.withinNormal===true&&<span className="table-sub">참고 범위 내</span>}
      {deviation.thresholdKind==='CONTROL_LIMIT'&&deviation.withinNormal===false&&<span className="table-sub">관리한계 이탈</span>}
     </td>
    </tr>)}
   </tbody>
  </table>
 </div>;
}
export function RequestContext({value}:{value:Obj}){
 return <div className="request-context">
  <p><strong>{value.requestType==='MAINTENANCE'?'설비 정비 요청':'공정 확인 요청'}</strong> · {value.equipmentId||value.incident?.equipmentId||value.targetProcessCode}</p>
  {value.brief&&<BriefDetails brief={value.brief}/>}
 </div>;
}
/** 요청·승인의 조사 맥락(brief) — 요청 이유, 편차, 미확정 후보, 관련 기록, 확인할 항목. 다른 팀 요청과 상급자 승인에 같이 쓴다 */
export function BriefDetails({brief}:{brief:Obj}){
 return <>
   <h3>요청 이유</h3>
   <p>{brief.reason}</p>
   {brief.deviations?.length>0&&<>
    <h3>관측된 편차</h3>
    <DeviationTable deviations={brief.deviations}/>
   </>}
   {brief.suspectedCauses?.length>0&&<>
    <h3>확인이 필요한 원인 후보</h3>
    {brief.suspectedCauses.map((cause:Obj,index:number)=><p key={index}>{cause.candidate||cause.causeId} <span className="small muted">· 미확정</span></p>)}
   </>}
   {brief.relatedRecords?.length>0&&<>
    <h3>관련 기록</h3>
    {brief.relatedRecords.map((record:Obj,index:number)=><p key={index}>{record.summary||record.evidenceId}</p>)}
   </>}
   {brief.askFor?.length>0&&<>
    <h3>확인할 항목</h3>
    <ul>{brief.askFor.map((item:unknown,index:number)=><li key={index}><DetailValue value={item}/></li>)}</ul>
   </>}
 </>;
}
export function PayloadDetails({value}:{value:Obj}){
 return <>
  <dl className="facts">
   {Object.entries(value||{}).filter(([key])=>key!=='brief').map(([key,item])=><div key={key}>
    <dt>{fieldLabels[key]||key}</dt>
    <dd><DetailValue value={item}/></dd>
   </div>)}
  </dl>
  {value?.brief&&<RequestContext value={value}/>}
 </>;
}
export function EvidenceList({ids,refs,label}:{ids:unknown[];refs:Obj[];label:string}){
 if(ids.length===0)return null;
 return <div className="evidence-list">
  <strong className="small">{label}</strong>
  {ids.map((id,index)=>{
   const evidence=refs.find(ref=>ref.evidenceId===id);
   return <details key={index}>
    <summary>{evidence?.description||evidence?.label||'근거 '+(index+1)}</summary>
    <p className="small muted">{String(id)}</p>
    {evidence?.type&&<p className="small">유형 · {evidence.type}</p>}
   </details>;
  })}
 </div>;
}
