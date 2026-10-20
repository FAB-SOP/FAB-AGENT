import {describe,it,expect} from 'vitest';
import {readFileSync} from 'node:fs';
import {findSensorExcursions} from '../src/api/sensor-observation';
import {gunzipSync} from 'node:zlib';
const core=JSON.parse(gunzipSync(readFileSync(new URL('./fixtures/sensor-cases.json.gz',import.meta.url))).toString()) as {runs:Obj[];sensorEnvelopes:Obj[];traces:Record<string,Obj[]>};
import type {SensorSample,Obj} from '../src/api/types';
function observations(lot:string){
 const run=core.runs.find(r=>r.lotId===lot&&r.equipmentId==='CMP-PM3')!;
 const traces=core.traces[run.runId];
 return traces.flatMap(t=>{
  const samples:SensorSample[]=t.values.map((value:number,index:number)=>{const elapsed=index*t.sourceIntervalMs;return {sensorId:t.sensorId,sensorName:t.sensorId,runId:run.runId,waferId:run.waferId,equipmentId:run.equipmentId,measuredAt:new Date(Date.parse(t.t0)+elapsed).toISOString(),value,unit:t.unit,quality:t.quality,recipeStep:elapsed<10000?'READY':elapsed<310000?'PROCESS':'END'}});
  return findSensorExcursions(samples,run.runStart,core.sensorEnvelopes.find(r=>r.process==='CMP'&&r.sensorId===t.sensorId));
 }).filter(o=>o!==null);
}
describe('frozen scenarios: sustained sensor segments',()=>{
 it('L006 highlights only the low head-pressure steady window',()=>{const o=observations('CURRENT-L006');expect(o).toHaveLength(1);expect(o[0].sensorId).toBe('head_pressure');expect(o[0].direction).toBe('LOW');expect(o[0].fromSeconds).toBeGreaterThan(100);expect(o[0].fromSeconds).toBeLessThan(106);expect(o[0].toSeconds).toBeGreaterThan(208);expect(o[0].toSeconds).toBeLessThan(214);expect(o[0].mean).toBeLessThan(2.7)});
 it('L007 highlights slurry flow rather than inventing a pressure fault',()=>{const o=observations('CURRENT-L007');expect(o).toHaveLength(1);expect(o[0].sensorId).toBe('slurry_flow');expect(o[0].fromSeconds).toBe(14);expect(o[0].toSeconds).toBe(309);expect(o[0].mean).toBeLessThan(111)});
 it('L009 final OOC does not manufacture a CMP sensor window',()=>{expect(observations('CURRENT-L009')).toEqual([])});
 it('normal L001 startup/shutdown ramps do not create mean alarms',()=>{expect(observations('CURRENT-L001')).toEqual([])});
});

describe('segment persistence and source gaps',()=>{
 const ref={lower:2.9,upper:3.1,smoothingSeconds:2,minDurationSeconds:5,ruleVersion:'TEST'};
 const start='2026-10-18T00:00:00Z';
 function samples(low:(sec:number)=>boolean){return Array.from({length:401},(_,k)=>({sensorId:'p',sensorName:'p',runId:'run',waferId:'w',equipmentId:'pm',recipeStep:'PROCESS',measuredAt:new Date(Date.parse(start)+k*100).toISOString(),value:low(k/10)?2:3,unit:'psi',quality:'GOOD'}))}
 it('does not turn a brief spike into a sustained interval',()=>{expect(findSensorExcursions(samples(sec=>sec>=20&&sec<21),start,ref)).toEqual([])});
 it('does not join short excursions across a missing-data gap',()=>{const a=samples(sec=>sec>=20&&sec<=29).filter(s=>{const sec=(Date.parse(s.measuredAt)-Date.parse(start))/1000;return sec<24||sec>26});expect(findSensorExcursions(a,start,ref)).toEqual([])});
 it('requires a sensor envelope and does not reuse run-mean limits',()=>{expect(findSensorExcursions(samples(()=>true),start,{lcl:2.9,ucl:3.1})).toEqual([])});
});
