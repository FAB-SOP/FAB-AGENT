import type {SensorSample,Obj} from './types';
export interface SensorObservation {
 sensorId:string;
 fromSeconds:number;
 toSeconds:number;
 confirmedAtSeconds:number;
 mean:number;
 unit:string;
 lower:number;
 upper:number;
 direction:'LOW'|'HIGH';
 ruleVersion:string;
}
type Direction='LOW'|'HIGH';
type OpenSegment={start:number;last:number;direction:Direction};
type TracePoint={seconds:number;value:number;unit:string;sensorId:string};
const EPSILON=1e-6;
/** 본 처리 시작 12초 뒤부터의 정상 취득 샘플을 run 시작 기준 초로 */
function processPoints(samples:SensorSample[],runStartMs:number):TracePoint[]{
 return samples
  .filter(sample=>sample.recipeStep==='PROCESS'
   &&Date.parse(sample.measuredAt)>=runStartMs+12000
   &&Number.isFinite(sample.value)
   &&sample.quality.startsWith('GOOD'))
  .map(sample=>({
   seconds:(Date.parse(sample.measuredAt)-runStartMs)/1000,
   value:sample.value,
   unit:sample.unit,
   sensorId:sample.sensorId,
  }))
  .sort((first,second)=>first.seconds-second.seconds);
}
/** 샘플 간격 중앙값의 1.8배를 넘으면 끊긴 구간으로 본다 */
function maxSampleGap(points:TracePoint[]){
 const intervals=points.slice(1)
  .map((point,index)=>point.seconds-points[index].seconds)
  .filter(interval=>interval>0)
  .sort((first,second)=>first-second);
 return (intervals[Math.floor(intervals.length/2)]||1)*1.8+.001;
}
function toObservation(points:TracePoint[],segment:OpenSegment,hold:number,envelope:Obj):SensorObservation{
 const inSegment=points.filter(point=>point.seconds>=segment.start&&point.seconds<=segment.last);
 return {
  sensorId:points[0].sensorId,
  fromSeconds:segment.start,
  toSeconds:segment.last,
  confirmedAtSeconds:segment.start+hold,
  mean:inSegment.reduce((total,point)=>total+point.value,0)/inSegment.length,
  unit:points[0].unit,
  lower:envelope.lower,
  upper:envelope.upper,
  direction:segment.direction,
  ruleVersion:envelope.ruleVersion,
 };
}
/** 직전 smoothing초의 이동평균 — 끊긴 구간에서는 reset으로 새로 시작한다 */
function movingMean(smoothing:number){
 let window:TracePoint[]=[];
 let sum=0;
 let head=0;
 let streamStart=0;
 return {
  reset(start:number){
   window=[];
   sum=0;
   head=0;
   streamStart=start;
  },
  add(point:TracePoint){
   window.push(point);
   sum+=point.value;
   while(head<window.length&&window[head].seconds<point.seconds-smoothing-EPSILON){
    sum-=window[head].value;
    head++;
   }
  },
  isWarmedUp:(point:TracePoint)=>!(point.seconds-streamStart<smoothing-EPSILON),
  value:()=>sum/(window.length-head),
 };
}
/** Demo segmentation only. Uses a separately calibrated synthetic sensor envelope,
 * never the run-mean I-chart, generator truth, or the final-thickness OOC flag. */
export function findSensorExcursions(samples:SensorSample[],runStart:string,envelope:Obj|undefined):SensorObservation[]{
 if(!envelope||!Number.isFinite(envelope.lower)||!Number.isFinite(envelope.upper)||envelope.lower>=envelope.upper)return [];
 const runStartMs=Date.parse(runStart);
 const smoothing=Number(envelope.smoothingSeconds);
 const hold=Number(envelope.minDurationSeconds);
 if(!(smoothing>0&&hold>0))return [];
 const points=processPoints(samples,runStartMs);
 if(points.length<2)return [];
 const maxGap=maxSampleGap(points);
 const found:SensorObservation[]=[];
 const average=movingMean(smoothing);
 average.reset(points[0].seconds);
 let segment:OpenSegment|null=null;
 const finishSegment=()=>{
  if(segment&&segment.last-segment.start>=hold-EPSILON)found.push(toObservation(points,segment,hold,envelope));
  segment=null;
 };
 for(let index=0;index<points.length;index++){
  const point=points[index];
  if(index&&point.seconds-points[index-1].seconds>maxGap){
   finishSegment();
   average.reset(point.seconds);
  }
  average.add(point);
  if(!average.isWarmedUp(point))continue;
  const mean=average.value();
  const direction=mean<envelope.lower?'LOW':mean>envelope.upper?'HIGH':null;
  if(!direction){
   finishSegment();
   continue;
  }
  const current=segment as OpenSegment|null;
  if(current?.direction!==direction){
   finishSegment();
   segment={start:point.seconds,last:point.seconds,direction};
  }else current.last=point.seconds;
 }
 finishSegment();
 return found;
}
