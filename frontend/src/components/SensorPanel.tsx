import {useEffect,useMemo,useRef,useState,type RefObject} from 'react';
import {useQueries} from '@tanstack/react-query';
import * as echarts from 'echarts/core';
import {LineChart} from 'echarts/charts';
import {
 GridComponent,TooltipComponent,DataZoomComponent,MarkLineComponent,MarkAreaComponent,AriaComponent,
} from 'echarts/components';
import {CanvasRenderer} from 'echarts/renderers';
echarts.use([
 LineChart,GridComponent,TooltipComponent,DataZoomComponent,MarkLineComponent,MarkAreaComponent,AriaComponent,CanvasRenderer,
]);
import {findSensorExcursions,type SensorObservation} from '../api/sensor-observation';
import {monitoring} from '../api/monitoring';
import {api} from '../api/client';
import {fmt,sensorNames,type Obj,type RawData,type SensorSample} from '../api/types';
import {Empty,ErrorMessage,Panel} from './ui';
const sensorColors=['#168777','#5a77c7','#b481cc','#df9e37','#bd5c67','#4c9ebc','#7f9471','#9b764b','#747c94'];
const isFiniteNumber=(value:unknown):value is number=>typeof value==='number'&&Number.isFinite(value);
const axisNumber=(value:number)=>value.toLocaleString('ko-KR',{maximumFractionDigits:4});
/** 선택한 레시피 단계의 샘플 — STEADY는 본 처리 시작 12초 뒤부터 */
function samplesForStep(samples:SensorSample[]|undefined,step:string,runStart:string){
 return samples?.filter(sample=>step==='ALL'||(step==='STEADY'
  ?sample.recipeStep==='PROCESS'&&Date.parse(sample.measuredAt)>=Date.parse(runStart)+12000
  :sample.recipeStep===step))||[];
}
function referenceMarkLine(reference:Obj){
 return {
  silent:true,
  symbol:'none',
  lineStyle:{type:'dashed',width:0.9,opacity:0.45},
  label:{color:'#7a878c',opacity:1,fontSize:14,position:'insideEndTop',formatter:'{b}'},
  data:[
   {name:'UCL · 참고',yAxis:reference.ucl,lineStyle:{color:'#c07c29'}},
   {name:'평균',yAxis:reference.center,label:{position:'insideStartBottom'},lineStyle:{color:'#17836c'}},
   {name:'LCL · 참고',yAxis:reference.lcl,label:{position:'insideEndBottom'},lineStyle:{color:'#c07c29'}},
   ...(reference.usl!=null?[{name:'USL',yAxis:reference.usl,lineStyle:{color:'#d95757'}}]:[]),
   ...(reference.lsl!=null?[{name:'LSL',yAxis:reference.lsl,lineStyle:{color:'#d95757'}}]:[]),
  ],
 };
}
type ChartInput={
 selected:string[];
 sensors:Obj[];
 references:Obj[];
 dataset:(RawData|undefined)[];
 observations:SensorObservation[];
 step:string;
 runStart:string;
 multi:boolean;
 guides:boolean;
 windowSeconds:number;
};
/** 센서 하나의 선 — 다중 선택이면 정상 run 평균 대비 표준화 */
function sensorSeries(input:ChartInput,sensorId:string,index:number){
 const reference=input.references.find(item=>item.sensorId===sensorId);
 const standardDeviation=reference?(reference.ucl-reference.lcl)/6:0;
 const samples=samplesForStep(input.dataset[index]?.samples,input.step,input.runStart);
 const runStartMs=Date.parse(input.runStart||samples[0]?.measuredAt||'');
 const segments=input.observations.filter(observation=>observation.sensorId===sensorId);
 const markArea=segments.length?{
  silent:true,
  itemStyle:{color:'rgba(214,146,48,0.10)'},
  label:{fontSize:14,color:'#9a6429',position:'insideTopLeft'},
  data:segments.map(observation=>[{name:'지속 편차 구간',xAxis:observation.fromSeconds},{xAxis:observation.toSeconds}]),
 }:undefined;
 return {
  markArea,
  name:sensorNames[sensorId]||sensorId,
  type:'line',
  triggerLineEvent:true,
  showSymbol:false,
  animation:false,
  lineStyle:{width:1.6,opacity:0.6},
  emphasis:{focus:'series',lineStyle:{width:2.4,opacity:1}},
  data:samples.map(sample=>[
   (Date.parse(sample.measuredAt)-runStartMs)/1000,
   input.multi?(standardDeviation?(sample.value-reference!.center)/standardDeviation:null):sample.value,
  ]),
  markLine:!input.multi&&input.guides&&reference?referenceMarkLine(reference):undefined,
 };
}
/** 기준선이 보이도록 y축 범위를 넓힌다 */
function guideBoundsFor(input:ChartInput,series:ReturnType<typeof sensorSeries>[]){
 const guideRef=!input.multi&&input.guides?input.references.find(reference=>reference.sensorId===input.selected[0]):undefined;
 const guideValues=guideRef
  ?[
   ...series.flatMap(line=>line.data.map(point=>point[1])),
   guideRef.lcl,guideRef.center,guideRef.ucl,guideRef.lsl,guideRef.usl,
  ].filter(isFiniteNumber)
  :[];
 const low=guideValues.length?Math.min(...guideValues):0;
 const high=guideValues.length?Math.max(...guideValues):0;
 const pad=Math.max((high-low)*.08,Math.abs(high)*.0001,0.000001);
 return guideValues.length?{min:low-pad,max:high+pad}:{};
}
function buildChartOption(input:ChartInput){
 const series=input.selected.map((sensorId,index)=>sensorSeries(input,sensorId,index));
 const guideBounds=guideBoundsFor(input,series);
 const times=series.flatMap(line=>line.data.map(point=>point[0])).filter(isFiniteNumber);
 const duration=times.length?Math.max(...times)-Math.min(...times):0;
 const zoomEnd=input.windowSeconds&&duration?Math.min(100,input.windowSeconds/duration*100):100;
 return {
  textStyle:{fontSize:14},
  color:sensorColors,
  animation:false,
  aria:{enabled:true},
  tooltip:{trigger:'axis',axisPointer:{triggerEmphasis:false},renderMode:'richText',textStyle:{fontSize:14}},
  grid:{left:78,right:35,top:30,bottom:76},
  xAxis:{
   type:'value',
   min:'dataMin',
   max:'dataMax',
   name:'경과 시간 (초)',
   nameLocation:'middle',
   nameGap:32,
   axisLabel:{fontSize:14,color:'#52636c',formatter:axisNumber},
   splitLine:{show:false},
  },
  yAxis:{
   type:'value',
   scale:true,
   ...guideBounds,
   name:input.multi?'정상 run 평균 대비 표준화':input.sensors.find(sensor=>sensor.sensorId===input.selected[0])?.unit||'',
   nameTextStyle:{fontSize:14},
   axisLabel:{fontSize:14,color:'#52636c',formatter:axisNumber},
   splitLine:{lineStyle:{color:'#e9eef0'}},
  },
  dataZoom:[
   {type:'inside',start:0,end:zoomEnd,filterMode:'none'},
   {type:'slider',start:0,end:zoomEnd,filterMode:'none',height:18,bottom:10,textStyle:{fontSize:14}},
  ],
  series,
 };
}
/** 확대 구간의 점 밀도에 맞춰 선 굵기를 바꾸며 차트를 붙인다 */
function useEchart(chartNode:RefObject<HTMLDivElement|null>,option:ReturnType<typeof buildChartOption>,selectedCount:number,multi:boolean){
 useEffect(()=>{
  if(!chartNode.current||!selectedCount)return;
  const chart=echarts.init(chartNode.current);
  chart.setOption(option);
  const allTimes=option.series.flatMap(line=>line.data.map(point=>point[0])).filter(isFiniteNumber);
  const startTime=allTimes.length?Math.min(...allTimes):0;
  const duration=allTimes.length?Math.max(...allTimes)-startTime:0;
  const adaptLineWidth=()=>{
   const zoom=(chart.getOption().dataZoom as Obj[]|undefined)?.[0];
   const from=startTime+duration*(Number(zoom?.start??0)/100);
   const to=startTime+duration*(Number(zoom?.end??100)/100);
   const plotWidth=Math.max(80,chart.getWidth()-113);
   chart.setOption({series:option.series.map(line=>{
    const visibleCount=line.data.filter(point=>typeof point[0]==='number'&&point[0]>=from&&point[0]<=to).length;
    const density=visibleCount/plotWidth;
    const width=Math.max(multi?1.2:1.35,(multi?2.05:2.3)-0.35*Math.log2(1+density));
    return {lineStyle:{width,opacity:0.6},emphasis:{lineStyle:{width:width+0.4,opacity:1}}};
   })});
  };
  adaptLineWidth();
  chart.on('datazoom',adaptLineWidth);
  const resizeObserver=new ResizeObserver(()=>{chart.resize();adaptLineWidth()});
  resizeObserver.observe(chartNode.current);
  return()=>{
   resizeObserver.disconnect();
   chart.off('datazoom',adaptLineWidth);
   chart.dispose();
  };
 },[option,selectedCount]);
}
function SensorOptions({sensors,selected,onToggle}:{sensors:Obj[];selected:string[];onToggle:(sensorId:string)=>void}){
 return <div className="sensor-options">
  {sensors.map(sensor=><label key={sensor.sensorId} className={'sensor-chip '+(selected.includes(sensor.sensorId)?'selected':'')}>
   <input type="checkbox" checked={selected.includes(sensor.sensorId)} onChange={()=>onToggle(sensor.sensorId)}/>
   {selected.includes(sensor.sensorId)&&<span
    aria-hidden="true"
    className="sensor-color"
    style={{background:sensorColors[selected.indexOf(sensor.sensorId)]}}
   />}
   <span>{sensorNames[sensor.sensorId]||sensor.sensorName||sensor.sensorId}</span>
   <span className="muted">{sensor.unit}</span>
  </label>)}
 </div>;
}
function ChartToolbar({guides,multi,windowSeconds,onWindowChange}:{
 guides:boolean;multi:boolean;windowSeconds:number;onWindowChange:(seconds:number)=>void;
}){
 return <div className="chart-toolbar">
  <span className="small">{guides?'정상 기준선 자동 표시':multi?'다중 선택 · 기준선 숨김':'기준선 없음'}</span>
  <label className="small">구간 확대 <select
   aria-label="시계열 표시 구간"
   value={windowSeconds}
   onChange={event=>onWindowChange(Number(event.target.value))}
  >
   <option value={0}>전체 구간</option>
   <option value={60}>60초 확대</option>
   <option value={30}>30초 확대</option>
  </select></label>
 </div>;
}
function ObservationNote({observation}:{observation:SensorObservation}){
 return <div className="sensor-observation">
  <strong>{sensorNames[observation.sensorId]||observation.sensorId} · {observation.direction==='LOW'?'저하':'상승'} 지속</strong>
  <span>조사 구간 {fmt(observation.fromSeconds)}
   –{fmt(observation.toSeconds)}초 · 평균 {fmt(observation.mean,2)} {observation.unit} · 구간 판정 범위 {fmt(observation.lower,2)}
   –{fmt(observation.upper,2)} {observation.unit}</span>
  <span>탐지 기준 · 2초 이동평균이 참고 범위를 5초 이상 이탈</span>
 </div>;
}
function SensorStats({selected,dataset,step,runStart}:{selected:string[];dataset:(RawData|undefined)[];step:string;runStart:string}){
 return <div className="sensor-stats">
  {selected.map((sensorId,index)=>{
   const stepSamples=samplesForStep(dataset[index]?.samples,step,runStart);
   const mean=stepSamples.length?stepSamples.reduce((total,sample)=>total+sample.value,0)/stepSamples.length:null;
   return <div key={sensorId}>
    <span>{sensorNames[sensorId]||sensorId}</span>
    <strong>{fmt(mean,2)} <small>{stepSamples[0]?.unit}</small></strong>
    <span className="small muted">선택 처리 구간 평균 · {stepSamples.length.toLocaleString()} 샘플</span>
   </div>;
  })}
 </div>;
}
function StepSelect({step,onChange}:{step:string;onChange:(step:string)=>void}){
 return <select aria-label="레시피 단계" value={step} onChange={event=>onChange(event.target.value)}>
  <option value="STEADY">본 처리 안정 구간</option>
  <option value="ALL">전체 처리 구간</option>
  <option value="PROCESS">본 처리 구간</option>
  <option value="READY">준비 구간</option>
  <option value="END">종료 구간</option>
 </select>;
}
/** 잘리지 않은 시계열에서만 지속 이탈 구간을 찾는다 */
function excursionsFor(selected:string[],dataset:(RawData|undefined)[],runStart:string,envelopes:Obj[]){
 return selected.flatMap((sensorId,index)=>dataset[index]&&!dataset[index]?.truncated
  ?findSensorExcursions(dataset[index]!.samples,runStart,envelopes.find(envelope=>envelope.sensorId===sensorId))
  :[]);
}
function ChartFoot({multi,hasSpec}:{multi:boolean;hasSpec:boolean}){
 return <div className="chart-foot small">
  <span>{multi?'중첩 표시 · 정상 run 평균 대비 표준화':'기준선 · 정상 run 평균 참고 범위'}</span>
  <span>USL/LSL: {hasSpec?'제공된 기준 사용':'미등록'}</span>
 </div>;
}
const toggledSelection=(selected:string[],sensorId:string)=>selected.includes(sensorId)
 ?selected.filter(item=>item!==sensorId)
 :[...selected,sensorId];
function sensorDataQuery(run:Obj,sensorId:string,incidentId?:string){
 return {
  queryKey:['sensor',run.runId,sensorId,incidentId],
  queryFn:()=>incidentId
   ?api<RawData>(`/api/incidents/${encodeURIComponent(incidentId)}/sensor-data?sensorId=${encodeURIComponent(sensorId)}&limit=5000`)
   :monitoring.sensor(run.runId,sensorId),
  staleTime:Infinity,
 };
}
export function SensorPanel({run,sensors,references,envelopes=[],incidentId}:{
 run:Obj;sensors:Obj[];references:Obj[];envelopes?:Obj[];incidentId?:string;
}){
 const [selected,setSelected]=useState<string[]>(sensors.slice(0,1).map(sensor=>sensor.sensorId));
 const [windowSeconds,setWindowSeconds]=useState(0);
 const [step,setStep]=useState('STEADY');
 const chartNode=useRef<HTMLDivElement>(null);
 useEffect(()=>{
  setSelected(sensors.slice(0,1).map(sensor=>sensor.sensorId));
  setWindowSeconds(0);
  setStep('STEADY');
 },[run.runId]);
 const queries=useQueries({queries:selected.map(sensorId=>sensorDataQuery(run,sensorId,incidentId))});
 const dataset=queries.map(query=>query.data);
 const multi=selected.length>1;
 const availableRef=references.find(reference=>reference.sensorId===selected[0]);
 const guides=selected.length===1&&!!availableRef;
 const observations=excursionsFor(selected,dataset,run.runStart,envelopes);
 const option=useMemo(
  ()=>buildChartOption({selected,sensors,references,dataset,observations,step,runStart:run.runStart,multi,guides,windowSeconds}),
  [JSON.stringify(dataset),JSON.stringify(envelopes),selected.join(','),guides,step,windowSeconds,run.runId],
 );
 useEchart(chartNode,option,selected.length,multi);
 const toggle=(sensorId:string)=>setSelected(previous=>toggledSelection(previous,sensorId));
 return <Panel title="센서 시계열" eyebrow="DURING PROCESS" extra={<StepSelect step={step} onChange={setStep}/>}>
  <SensorOptions sensors={sensors} selected={selected} onToggle={toggle}/>
  <ChartToolbar guides={guides} multi={multi} windowSeconds={windowSeconds} onWindowChange={setWindowSeconds}/>

  {queries.some(query=>query.isError)&&<ErrorMessage error={queries.find(query=>query.error)?.error}/>}
  {!selected.length
   ?<Empty>위 체크박스에서 확인할 센서를 선택해 주세요.</Empty>
   :<div className="chart" ref={chartNode} role="img" aria-label="선택한 웨이퍼 처리 중 센서 시계열"/>}
  {queries.some(query=>query.isPending)&&<p className="small muted">센서 원본을 불러오는 중…</p>}
  {queries.some(query=>query.data?.truncated)&&<p className="small warning-text">일부 데이터만 표시 중 · 조회 구간을 줄여주세요.</p>}
  {observations.map(observation=><ObservationNote key={observation.sensorId+observation.fromSeconds} observation={observation}/>)}
  <ChartFoot multi={multi} hasSpec={availableRef?.usl!=null}/>
  <SensorStats selected={selected} dataset={dataset} step={step} runStart={run.runStart}/>
 </Panel>
}
