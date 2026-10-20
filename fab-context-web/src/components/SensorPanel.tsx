import {useEffect,useMemo,useRef,useState} from 'react';
import {useQueries} from '@tanstack/react-query';
import * as echarts from 'echarts/core';
import {LineChart} from 'echarts/charts';
import {GridComponent,TooltipComponent,DataZoomComponent,MarkLineComponent,MarkAreaComponent,AriaComponent} from 'echarts/components';
import {CanvasRenderer} from 'echarts/renderers';
echarts.use([LineChart,GridComponent,TooltipComponent,DataZoomComponent,MarkLineComponent,MarkAreaComponent,AriaComponent,CanvasRenderer]);
import {findSensorExcursions} from '../api/sensor-observation';
import {monitoring} from '../api/monitoring';
import {api} from '../api/client';
import {fmt,sensorNames,type Obj,type RawData} from '../api/types';
import {Empty,ErrorMessage,Panel} from './ui';
const sensorColors=['#168777','#5a77c7','#b481cc','#df9e37','#bd5c67','#4c9ebc','#7f9471','#9b764b','#747c94'];
export function SensorPanel({run,sensors,references,envelopes=[],incidentId}:{run:Obj;sensors:Obj[];references:Obj[];envelopes?:Obj[];incidentId?:string}){
 const [selected,setSelected]=useState<string[]>(sensors.slice(0,1).map(s=>s.sensorId));const [windowSeconds,setWindowSeconds]=useState(0);const [step,setStep]=useState('STEADY');const node=useRef<HTMLDivElement>(null);
 useEffect(()=>{setSelected(sensors.slice(0,1).map(s=>s.sensorId));setWindowSeconds(0);setStep('STEADY')},[run.runId]);
 const queries=useQueries({queries:selected.map(sensor=>({queryKey:['sensor',run.runId,sensor,incidentId],queryFn:()=>incidentId?api<RawData>(`/api/incidents/${encodeURIComponent(incidentId)}/sensor-data?sensorId=${encodeURIComponent(sensor)}&limit=5000`):monitoring.sensor(run.runId,sensor),staleTime:Infinity}))});
 const dataset=queries.map(q=>q.data);const multi=selected.length>1;const availableRef=references.find(r=>r.sensorId===selected[0]);
 const guides=selected.length===1&&!!availableRef;
 const observations=selected.flatMap((sid,k)=>dataset[k]&&!dataset[k]?.truncated?findSensorExcursions(dataset[k]!.samples,run.runStart,envelopes.find(r=>r.sensorId===sid)):[]);
 const option=useMemo(()=>{
  const series=selected.map((sid,k)=>{const ref=references.find(r=>r.sensorId===sid);const sd=ref?(ref.ucl-ref.lcl)/6:0;const raw=dataset[k]?.samples.filter(s=>step==='ALL'||(step==='STEADY'?s.recipeStep==='PROCESS'&&Date.parse(s.measuredAt)>=Date.parse(run.runStart)+12000:s.recipeStep===step))||[];const t0=Date.parse(run.runStart||raw[0]?.measuredAt||'');
   const segments=observations.filter(o=>o.sensorId===sid);
   const markArea=segments.length?{silent:true,itemStyle:{color:'rgba(214,146,48,0.10)'},label:{fontSize:14,color:'#9a6429',position:'insideTopLeft'},data:segments.map(o=>[{name:'지속 편차 구간',xAxis:o.fromSeconds},{xAxis:o.toSeconds}])}:undefined;
   return {markArea,name:sensorNames[sid]||sid,type:'line',triggerLineEvent:true,showSymbol:false,animation:false,lineStyle:{width:1.6,opacity:0.6},emphasis:{focus:'series',lineStyle:{width:2.4,opacity:1}},data:raw.map(s=>[(Date.parse(s.measuredAt)-t0)/1000,multi?(sd?(s.value-ref!.center)/sd:null):s.value]),markLine:!multi&&guides&&ref?{silent:true,symbol:'none',lineStyle:{type:'dashed',width:0.9,opacity:0.45},label:{color:'#7a878c',opacity:1,fontSize:14,position:'insideEndTop',formatter:'{b}'},data:[{name:'UCL · 참고',yAxis:ref.ucl,lineStyle:{color:'#c07c29'}},{name:'평균',yAxis:ref.center,label:{position:'insideStartBottom'},lineStyle:{color:'#17836c'}},{name:'LCL · 참고',yAxis:ref.lcl,label:{position:'insideEndBottom'},lineStyle:{color:'#c07c29'}},...(ref.usl!=null?[{name:'USL',yAxis:ref.usl,lineStyle:{color:'#d95757'}}]:[]),...(ref.lsl!=null?[{name:'LSL',yAxis:ref.lsl,lineStyle:{color:'#d95757'}}]:[])]}:undefined};
  });
  const guideRef=!multi&&guides?references.find(r=>r.sensorId===selected[0]):undefined;
  const guideValues=guideRef?[...series.flatMap(s=>s.data.map(point=>point[1])),guideRef.lcl,guideRef.center,guideRef.ucl,guideRef.lsl,guideRef.usl].filter((v):v is number=>typeof v==='number'&&Number.isFinite(v)):[];
  const low=guideValues.length?Math.min(...guideValues):0;const high=guideValues.length?Math.max(...guideValues):0;const pad=Math.max((high-low)*.08,Math.abs(high)*.0001,0.000001);
  const guideBounds=guideValues.length?{min:low-pad,max:high+pad}:{};
  const times=series.flatMap(s=>s.data.map(point=>point[0])).filter((v):v is number=>typeof v==='number'&&Number.isFinite(v));const duration=times.length?Math.max(...times)-Math.min(...times):0;
  const zoomEnd=windowSeconds&&duration?Math.min(100,windowSeconds/duration*100):100;
  return {textStyle:{fontSize:14},color:sensorColors,animation:false,aria:{enabled:true},tooltip:{trigger:'axis',axisPointer:{triggerEmphasis:false},renderMode:'richText',textStyle:{fontSize:14}},grid:{left:78,right:35,top:30,bottom:76},xAxis:{type:'value',min:'dataMin',max:'dataMax',name:'경과 시간 (초)',nameLocation:'middle',nameGap:32,axisLabel:{fontSize:14,color:'#52636c',formatter:(value:number)=>value.toLocaleString('ko-KR',{maximumFractionDigits:4})},splitLine:{show:false}},yAxis:{type:'value',scale:true,...guideBounds,name:multi?'정상 run 평균 대비 표준화':sensors.find(s=>s.sensorId===selected[0])?.unit||'',nameTextStyle:{fontSize:14},axisLabel:{fontSize:14,color:'#52636c',formatter:(value:number)=>value.toLocaleString('ko-KR',{maximumFractionDigits:4})},splitLine:{lineStyle:{color:'#e9eef0'}}},dataZoom:[{type:'inside',start:0,end:zoomEnd,filterMode:'none'},{type:'slider',start:0,end:zoomEnd,filterMode:'none',height:18,bottom:10,textStyle:{fontSize:14}}],series};
 },[JSON.stringify(dataset),JSON.stringify(envelopes),selected.join(','),guides,step,windowSeconds,run.runId]);
 useEffect(()=>{
  if(!node.current||!selected.length)return;
  const chart=echarts.init(node.current);chart.setOption(option);
  const allTimes=option.series.flatMap(s=>s.data.map(point=>point[0])).filter((v):v is number=>typeof v==='number'&&Number.isFinite(v));
  const startTime=allTimes.length?Math.min(...allTimes):0;const duration=allTimes.length?Math.max(...allTimes)-startTime:0;
  const adaptLineWidth=()=>{
   const zoom=(chart.getOption().dataZoom as Obj[]|undefined)?.[0];
   const from=startTime+duration*(Number(zoom?.start??0)/100);const to=startTime+duration*(Number(zoom?.end??100)/100);
   const plotWidth=Math.max(80,chart.getWidth()-113);
   chart.setOption({series:option.series.map(s=>{
    const visibleCount=s.data.filter(point=>typeof point[0]==='number'&&point[0]>=from&&point[0]<=to).length;
    const density=visibleCount/plotWidth;
    const width=Math.max(multi?1.2:1.35,(multi?2.05:2.3)-0.35*Math.log2(1+density));
    return {lineStyle:{width,opacity:0.6},emphasis:{lineStyle:{width:width+0.4,opacity:1}}};
   })});
  };
  adaptLineWidth();chart.on('datazoom',adaptLineWidth);
  const obs=new ResizeObserver(()=>{chart.resize();adaptLineWidth()});obs.observe(node.current);
  return()=>{obs.disconnect();chart.off('datazoom',adaptLineWidth);chart.dispose()};
 },[option,selected.length]);
 const toggle=(id:string)=>setSelected(old=>old.includes(id)?old.filter(s=>s!==id):[...old,id]);
 return <Panel title="센서 시계열" eyebrow="DURING PROCESS" extra={<select aria-label="레시피 단계" value={step} onChange={e=>setStep(e.target.value)}><option value="STEADY">본 처리 안정 구간</option><option value="ALL">전체 처리 구간</option><option value="PROCESS">본 처리 구간</option><option value="READY">준비 구간</option><option value="END">종료 구간</option></select>}>
  <div className="sensor-options">{sensors.map((s,k)=><label key={s.sensorId} className={'sensor-chip '+(selected.includes(s.sensorId)?'selected':'')}><input type="checkbox" checked={selected.includes(s.sensorId)} onChange={()=>toggle(s.sensorId)}/>{selected.includes(s.sensorId)&&<span aria-hidden="true" className="sensor-color" style={{background:sensorColors[selected.indexOf(s.sensorId)]}}/>}<span>{sensorNames[s.sensorId]||s.sensorName||s.sensorId}</span><span className="muted">{s.unit}</span></label>)}</div>
  <div className="chart-toolbar"><span className="small">{guides?'정상 기준선 자동 표시':multi?'다중 선택 · 기준선 숨김':'기준선 없음'}</span><label className="small">구간 확대 <select aria-label="시계열 표시 구간" value={windowSeconds} onChange={e=>setWindowSeconds(Number(e.target.value))}><option value={0}>전체 구간</option><option value={60}>60초 확대</option><option value={30}>30초 확대</option></select></label></div>

  {queries.some(q=>q.isError)&&<ErrorMessage error={queries.find(q=>q.error)?.error}/>}
  {!selected.length?<Empty>위 체크박스에서 확인할 센서를 선택해 주세요.</Empty>:<div className="chart" ref={node} role="img" aria-label="선택한 웨이퍼 처리 중 센서 시계열"/>}
  {queries.some(q=>q.isPending)&&<p className="small muted">센서 원본을 불러오는 중…</p>}
  {queries.some(q=>q.data?.truncated)&&<p className="small warning-text">일부 데이터만 표시 중 · 조회 구간을 줄여주세요.</p>}
  {observations.map(o=><div className="sensor-observation" key={o.sensorId+o.fromSeconds}><strong>{sensorNames[o.sensorId]||o.sensorId} · {o.direction==='LOW'?'저하':'상승'} 지속</strong><span>조사 구간 {fmt(o.fromSeconds)}–{fmt(o.toSeconds)}초 · 평균 {fmt(o.mean,2)} {o.unit} · 구간 판정 범위 {fmt(o.lower,2)}–{fmt(o.upper,2)} {o.unit}</span><span>탐지 기준 · 2초 이동평균이 참고 범위를 5초 이상 이탈</span></div>)}
  <div className="chart-foot small"><span>{multi?'중첩 표시 · 정상 run 평균 대비 표준화':'기준선 · 정상 run 평균 참고 범위'}</span><span>USL/LSL: {availableRef?.usl==null?'미등록':'제공된 기준 사용'}</span></div>
  <div className="sensor-stats">{selected.map((sid,k)=>{const a=dataset[k]?.samples.filter(s=>step==='ALL'||(step==='STEADY'?s.recipeStep==='PROCESS'&&Date.parse(s.measuredAt)>=Date.parse(run.runStart)+12000:s.recipeStep===step))||[];const mean=a.length?a.reduce((n,s)=>n+s.value,0)/a.length:null;return <div key={sid}><span>{sensorNames[sid]||sid}</span><strong>{fmt(mean,2)} <small>{a[0]?.unit}</small></strong><span className="small muted">선택 처리 구간 평균 · {a.length.toLocaleString()} 샘플</span></div>})}</div>
 </Panel>
}
