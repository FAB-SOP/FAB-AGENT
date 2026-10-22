import {api} from './client';
import type {Obj,RawData} from './types';
/** Public monitoring contract implemented by fab-context-api. */
export const monitoring={
 lots:(process:string)=>api<{items:string[]}>(`/api/monitoring/lots?processCode=${encodeURIComponent(process)}`),
 summary:(lot:string,process:string)=>api<Obj>(
  `/api/monitoring/lots/${encodeURIComponent(lot)}/summary?processCode=${encodeURIComponent(process)}`,
 ),
 /** 공정의 센서 정의 — 센서 시계열과 같은 목록(보고서 원인 분류의 장비 파라미터 선택지) */
 sensorDefinitions:(process:string)=>api<{processCode:string;items:Obj[]}>(
  `/api/monitoring/sensor-definitions?processCode=${encodeURIComponent(process)}`,
 ),
 sensor:(run:string,sensor:string)=>api<RawData>(
  `/api/monitoring/runs/${encodeURIComponent(run)}/sensor-data?sensorId=${encodeURIComponent(sensor)}&limit=5000`,
 ),
};
