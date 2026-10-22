import {sensorNames,type CauseCategory,type Obj,type ReportClassification} from '../api/types';
export const causeCategories:CauseCategory[]=['MATERIAL','MAN','METHOD','MACHINE','HOLD'];
export const causeCategoryLabels:Record<CauseCategory,string>={
 MATERIAL:'Material (자재)',
 MAN:'Man (사람)',
 METHOD:'Method (방법)',
 MACHINE:'Machine (설비)',
 HOLD:'보류 (원인 미확정)',
};
/** 화면에서 고르는 중인 분류 — category가 비어 있으면 아직 고르지 않은 것 */
export type ClassificationDraft={category:CauseCategory|'';equipmentParameters:string[]};
export const emptyClassificationDraft:ClassificationDraft={category:'',equipmentParameters:[]};
export const requiresEquipmentParameters=(category:CauseCategory|'')=>category==='MACHINE';
/** 확정 · 승인할 수 있을 만큼 골랐는가 — Machine이면 장비 파라미터 1개 이상 */
export function isClassificationComplete(draft:ClassificationDraft){
 if(!draft.category)return false;
 return !requiresEquipmentParameters(draft.category)||draft.equipmentParameters.length>0;
}
/** 범주를 바꾸면 Machine이 아닐 때 고른 파라미터를 지운다 */
export function changeCategory(draft:ClassificationDraft,category:CauseCategory|''):ClassificationDraft{
 return {category,equipmentParameters:requiresEquipmentParameters(category)?draft.equipmentParameters:[]};
}
export function toggleEquipmentParameter(draft:ClassificationDraft,sensorId:string):ClassificationDraft{
 const selected=draft.equipmentParameters.includes(sensorId);
 return {...draft,equipmentParameters:selected
  ?draft.equipmentParameters.filter(id=>id!==sensorId)
  :[...draft.equipmentParameters,sensorId]};
}
/** 서버 요청 본문 — Machine이 아니면 파라미터를 보내지 않는다 */
export function toClassificationRequest(draft:ClassificationDraft){
 return {
  category:draft.category,
  equipmentParameters:requiresEquipmentParameters(draft.category)?draft.equipmentParameters:[],
 };
}
export type EquipmentOption={sensorId:string;label:string;unit:string;related:boolean};
/** 센서 시계열과 같은 표시 이름 — 사건 관련 센서를 먼저, 나머지는 카탈로그 순서 */
export function orderEquipmentOptions(sensors:Obj[],incidentSensorIds:string[]=[]):EquipmentOption[]{
 const options=sensors.map(sensor=>({
  sensorId:String(sensor.sensorId),
  label:sensorNames[sensor.sensorId]||String(sensor.sensorId),
  unit:sensor.unit||'',
  related:incidentSensorIds.includes(sensor.sensorId),
 }));
 return [...options.filter(option=>option.related),...options.filter(option=>!option.related)];
}
export const equipmentParameterLabel=(sensorId:string)=>sensorNames[sensorId]||sensorId;
/** 확정본 · 목록에 보이는 한 줄 — 분류가 없으면 「분류 없음」 */
export function describeClassification(classification?:ReportClassification|null){
 if(!classification)return '분류 없음';
 const label=causeCategoryLabels[classification.category]||classification.category;
 const parameters=classification.equipmentParameters||[];
 return parameters.length?`${label} — ${parameters.map(equipmentParameterLabel).join(', ')}`:label;
}
