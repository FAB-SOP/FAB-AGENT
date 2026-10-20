import type {SensorSample,Obj} from './types';
export interface SensorObservation {sensorId:string;fromSeconds:number;toSeconds:number;confirmedAtSeconds:number;mean:number;unit:string;lower:number;upper:number;direction:'LOW'|'HIGH';ruleVersion:string}
/** Demo segmentation only. Uses a separately calibrated synthetic sensor envelope,
 * never the run-mean I-chart, generator truth, or the final-thickness OOC flag. */
export function findSensorExcursions(samples:SensorSample[],runStart:string,envelope:Obj|undefined):SensorObservation[]{
 if(!envelope||!Number.isFinite(envelope.lower)||!Number.isFinite(envelope.upper)||envelope.lower>=envelope.upper)return [];
 const t0=Date.parse(runStart);const smoothing=Number(envelope.smoothingSeconds);const hold=Number(envelope.minDurationSeconds);
 if(!(smoothing>0&&hold>0))return [];
 const data=samples.filter(s=>s.recipeStep==='PROCESS'&&Date.parse(s.measuredAt)>=t0+12000&&Number.isFinite(s.value)&&s.quality.startsWith('GOOD')).map(s=>({t:(Date.parse(s.measuredAt)-t0)/1000,value:s.value,unit:s.unit,sensorId:s.sensorId})).sort((a,b)=>a.t-b.t);
 if(data.length<2)return [];
 const diffs=data.slice(1).map((p,k)=>p.t-data[k].t).filter(x=>x>0).sort((a,b)=>a-b);const maxGap=(diffs[Math.floor(diffs.length/2)]||1)*1.8+.001;
 const found:SensorObservation[]=[];let rolling:typeof data=[];let sum=0;let head=0;let segment:{start:number;last:number;direction:'LOW'|'HIGH'}|null=null;let first=data[0].t;
 const finish=()=>{if(segment&&segment.last-segment.start>=hold-1e-6){const part=data.filter(p=>p.t>=segment!.start&&p.t<=segment!.last);found.push({sensorId:data[0].sensorId,fromSeconds:segment.start,toSeconds:segment.last,confirmedAtSeconds:segment.start+hold,mean:part.reduce((n,p)=>n+p.value,0)/part.length,unit:data[0].unit,lower:envelope.lower,upper:envelope.upper,direction:segment.direction,ruleVersion:envelope.ruleVersion})}segment=null};
 for(let k=0;k<data.length;k++){
  const p=data[k];if(k&&p.t-data[k-1].t>maxGap){finish();rolling=[];sum=0;head=0;first=p.t}
  rolling.push(p);sum+=p.value;while(head<rolling.length&&rolling[head].t<p.t-smoothing-1e-6){sum-=rolling[head].value;head++}
  if(p.t-first<smoothing-1e-6)continue;
  const mean=sum/(rolling.length-head);const direction=mean<envelope.lower?'LOW':mean>envelope.upper?'HIGH':null;
  if(!direction){finish();continue}
  const current=segment as {start:number;last:number;direction:'LOW'|'HIGH'}|null;
  if(current?.direction!==direction){finish();segment={start:p.t,last:p.t,direction}}else current.last=p.t;
 }
 finish();return found;
}
