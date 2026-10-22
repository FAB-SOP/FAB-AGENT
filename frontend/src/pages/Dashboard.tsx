import {useEffect} from 'react';
import {Link,useSearchParams} from 'react-router-dom';
import {useQuery} from '@tanstack/react-query';
import {ArrowUpRight,CheckCircle2,TriangleAlert,RefreshCw} from 'lucide-react';
import {monitoring} from '../api/monitoring';
import {api} from '../api/client';
import {fmt,when,type Obj} from '../api/types';
import {Panel,PageHeading,Notice,Empty,ErrorMessage} from '../components/ui';
import {SensorPanel} from '../components/SensorPanel';
const siteNames:Obj={CENTER:'중앙',NORTH:'북',EAST:'동',SOUTH:'남',WEST:'서'};
const metrologyTime=new Intl.DateTimeFormat('ko-KR',{
 month:'2-digit',day:'2-digit',hour:'2-digit',minute:'2-digit',second:'2-digit',hour12:false,timeZone:'Asia/Seoul',
});
function KpiRow({counts,process}:{counts?:Obj;process:string}){
 return <div className="kpi-row">
  <div><span>미종결 이상</span><strong>{counts?.active??'—'}<small>건</small></strong></div>
  <div><span>검토 대기 제안</span><strong>{counts?.pendingApprovals??'—'}<small>건</small></strong></div>
  <div><span>받은 확인 요청</span><strong>{counts?.openRequests??'—'}<small>건</small></strong></div>
  <div className="kpi-context"><span>관찰 범위</span><strong>{process}<small>담당 공정</small></strong></div>
 </div>;
}
function LotToolbar({lot,lots,onSelect}:{lot:string;lots?:string[];onSelect:(lot:string)=>void}){
 return <div className="section-toolbar">
  <div>
   <h2>설비별 웨이퍼 결과</h2>
   <p className="muted">각 PM당 웨이퍼 한 장 · 한 장 내 5위치 평균</p>
  </div>
  <label className="inline-field">로트<select aria-label="로트 선택" value={lot} onChange={event=>onSelect(event.target.value)}>
   {(lots||[lot]).map(lotId=><option key={lotId} value={lotId}>{lotId.replace('CURRENT-','')}</option>)}
  </select></label>
 </div>;
}
function PmCard({run,active,limits,process,onSelect}:{run:Obj;active:boolean;limits?:Obj;process:string;onSelect:()=>void}){
 const deltaFromCenter=limits
  ?`${run.outputMeanA-limits.center>=0?'+':''}${fmt(run.outputMeanA-limits.center)} Å`
  :'—';
 return <button className={'pm-card '+(run.oocR1?'alert ':'')+(active?'active':'')} onClick={onSelect}>
  <div className="pm-top">
   <span className="equipment-icon">{run.equipmentId.split('-').at(-1)}</span>
   <span className={'status-dot '+(run.oocR1?'red':'')}>
    {run.oocR1?<TriangleAlert size={16}/>:<CheckCircle2 size={16}/>} {run.oocR1?'OOC · 규격 내':'관리 범위 내'}</span>
  </div>
  <p className="small muted">{process==='ETCH'?'식각 후 잔막':'공정 후 평균 두께'}</p>
  <div className="pm-value">{fmt(run.outputMeanA)}<span>Å</span></div>
  <div className="pm-meta">
   <span>평균 기준 대비 <strong>{deltaFromCenter}</strong></span>
   <ArrowUpRight size={19}/>
  </div>
 </button>;
}
function LimitSummary({limits}:{limits:Obj}){
 return <div className="limit-summary small">
  <span>동일 조건·매칭 PM의 고정 기준</span>
  <span>LCL <b>{fmt(limits.lcl)}</b></span>
  <span>평균 <b>{fmt(limits.center)}</b></span>
  <span>UCL <b>{fmt(limits.ucl)}</b> Å</span>
  <span>{limits.usl!=null?`가상 규격 ${fmt(limits.lsl,0)}–${fmt(limits.usl,0)} Å`:'제품 규격 미등록'}</span>
 </div>;
}
function MetrologyPanel({run,limits}:{run:Obj;limits?:Obj}){
 return <Panel title="공정 후 두께 계측" eyebrow="AFTER PROCESS">
  {run.oocR1&&<div className="metrology-detection" role="status">
   <strong><TriangleAlert size={18}/> 공정 후 계측에서 OOC 검출</strong>
   <span>계측 시각 · {metrologyTime.format(new Date(run.metrologyAvailableAt))} KST</span>
   <span>평균 두께 {fmt(run.outputMeanA)} Å · {run.outputMeanA>limits?.ucl?`UCL ${fmt(limits!.ucl)} Å 초과`:`LCL ${fmt(limits?.lcl)} Å 미만`}</span>
  </div>}
  <div className="metrology-mean">
   <span>한 장 내 5위치 평균</span>
   <strong>{fmt(run.outputMeanA)} <small>Å</small></strong>
  </div>
  <div className="wafer-map" aria-label="웨이퍼 5위치 두께 측정">
   {run.sites.map((site:Obj)=><div key={site.siteId} className={'wafer-site '+site.siteId.toLowerCase()}>
    <span>{siteNames[site.siteId]}</span>
    <b>{fmt(site.filmThicknessA,0)}</b>
   </div>)}
  </div>
  <p className="small muted">처리 종료 후 측정 결과</p>
 </Panel>;
}
function ResultSummaryPanel({run,process}:{run:Obj;process:string}){
 const thicknesses=run.sites.map((site:Obj)=>site.filmThicknessA);
 return <Panel title="처리 결과 요약">
  <dl className="facts">
   <div>
    <dt>{process==='CVD'?'증착량':'입고 두께'}</dt>
    <dd>{fmt(process==='CVD'?run.addedOrRemovedA:run.inputMeanA)} Å</dd>
   </div>
   {process!=='CVD'&&<div><dt>제거량</dt><dd>{fmt(run.addedOrRemovedA)} Å</dd></div>}
   <div><dt>본 처리 시간</dt><dd>{run.processSeconds}초</dd></div>
   <div>
    <dt>위치 간 최대 차이</dt>
    <dd>{fmt(Math.max(...thicknesses)-Math.min(...thicknesses))} Å</dd>
   </div>
   <div><dt>규격 판정</dt><dd>{run.oos===null?'규격 미등록':run.oos?'규격 밖':'규격 내'}</dd></div>
  </dl>
 </Panel>;
}
function EquipmentDetail({run,data,limits,process}:{run:Obj;data:Obj;limits?:Obj;process:string}){
 return <>
  <div className="detail-heading">
   <div>
    <p className="eyebrow">SELECTED EQUIPMENT</p>
    <h2>{run.equipmentId} <span>웨이퍼 상세</span></h2>
    <p className="muted small">{run.waferId} · {run.recipeId} · {when(run.runStart)} KST</p>
   </div>
   {run.incidentId
    ?<Link className="button" to={'/incidents/'+run.incidentId}>이상 조사 열기 <ArrowUpRight size={18}/></Link>
    :<span className="badge normal">해당 run의 이상 사건 없음</span>}
  </div>
  <div className="detail-grid">
   <SensorPanel
    key={run.runId}
    run={run}
    sensors={data.sensorDefinitions}
    references={data.references}
    envelopes={data.sensorEnvelopes||[]}
   />
   <div className="side-stack">
    <MetrologyPanel run={run} limits={limits}/>
    <ResultSummaryPanel run={run} process={process}/>
   </div>
  </div>
 </>;
}
export default function Dashboard({process}:{process:string}){
 const [search,setSearch]=useSearchParams();
 const lot=search.get('lot')||'CURRENT-L009';
 const lots=useQuery({queryKey:['lots',process],queryFn:()=>monitoring.lots(process)});
 const summary=useQuery({queryKey:['monitoring',process,lot],queryFn:()=>monitoring.summary(lot,process)});
 const stats=useQuery({queryKey:['dashboard',process],queryFn:()=>api('/api/dashboard?processCode='+process),refetchInterval:10000});
 const data=summary.data;
 const runs:Obj[]=data?.runs||[];
 const active=runs.find(run=>run.equipmentId===search.get('pm'))||runs.find(run=>run.oocR1)||runs[0];
 const limits=data?.limits;
 useEffect(()=>{
  if(lots.data?.items.length&&!lots.data.items.includes(lot))setSearch({lot:lots.data.items[0]},{replace:true});
 },[lots.data,process,lot]);
 const refreshButton=<button className="button secondary" onClick={()=>{summary.refetch();stats.refetch()}}><RefreshCw size={18}/>새로고침</button>;
 return <><PageHeading
  kicker="PROCESS MONITORING"
  title={`${process} 공정 대시보드`}
  description="로트의 설비별 결과를 확인하고, 한 웨이퍼의 계측과 센서 데이터를 함께 조사합니다."
  extra={refreshButton}
 />
  <KpiRow counts={stats.data?.counts} process={process}/>
  <LotToolbar lot={lot} lots={lots.data?.items} onSelect={value=>setSearch({lot:value})}/>
  {summary.isError&&<ErrorMessage error={summary.error} retry={()=>summary.refetch()}/>}
  {summary.isError&&<Notice>모니터링 데이터를 불러오지 못했습니다. 서버 연결과 담당 공정 권한을 확인한 뒤 다시 시도해 주세요.</Notice>}
  {summary.isPending&&<Empty>선택한 로트의 데이터를 불러오는 중…</Empty>}
  <div className="pm-grid">
   {runs.map(run=><PmCard
    key={run.runId}
    run={run}
    active={active?.runId===run.runId}
    limits={limits}
    process={process}
    onSelect={()=>setSearch({lot,pm:run.equipmentId})}
   />)}
  </div>
  {limits&&<LimitSummary limits={limits}/>}
  {active&&<EquipmentDetail run={active} data={data!} limits={limits} process={process}/>}
 </>;
}
