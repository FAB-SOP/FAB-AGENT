import {useQuery} from '@tanstack/react-query';
import {monitoring} from '../api/monitoring';
import type {CauseCategory} from '../api/types';
import {
 causeCategories,causeCategoryLabels,changeCategory,orderEquipmentOptions,requiresEquipmentParameters,
 toggleEquipmentParameter,type ClassificationDraft,
} from '../domain/causeClassification';
import {Field} from './ui';
/** 공정의 장비 파라미터 선택지 — 공정 대시보드 센서 시계열과 같은 센서 목록 */
export function useEquipmentOptions(processCode:string,incidentSensorIds:string[]=[]){
 const sensors=useQuery({
  queryKey:['sensor-definitions',processCode],
  queryFn:()=>monitoring.sensorDefinitions(processCode),
  enabled:!!processCode,
  staleTime:Infinity,
 });
 return {
  options:orderEquipmentOptions(sensors.data?.items||[],incidentSensorIds),
  isLoading:sensors.isLoading,
  isError:sensors.isError,
 };
}
/** 원인 분류 선택 — 4M · 보류 중 하나, Machine이면 장비 파라미터를 하나 이상 */
export function CauseClassificationSelect({value,onChange,processCode,incidentSensorIds,disabled=false}:{
 value:ClassificationDraft;
 onChange:(draft:ClassificationDraft)=>void;
 processCode:string;
 incidentSensorIds?:string[];
 disabled?:boolean;
}){
 const equipment=useEquipmentOptions(requiresEquipmentParameters(value.category)?processCode:'',incidentSensorIds);
 return <>
  <Field label="원인 분류">
   <select
    value={value.category}
    disabled={disabled}
    onChange={event=>onChange(changeCategory(value,event.target.value as CauseCategory|''))}
   >
    <option value="">원인 분류를 선택하세요</option>
    {causeCategories.map(category=><option key={category} value={category}>{causeCategoryLabels[category]}</option>)}
   </select>
  </Field>
  {requiresEquipmentParameters(value.category)&&<Field label="장비 파라미터 (하나 이상)">
   {equipment.isLoading?<p className="small muted">센서 목록을 불러오는 중…</p>
    :equipment.isError||!equipment.options.length?<p className="warning-text">장비 파라미터 목록을 불러오지 못했습니다. Machine으로는 확정할 수 없습니다.</p>
     :<div className="sensor-options">
      {equipment.options.map(option=><label
       key={option.sensorId}
       className={'sensor-chip '+(value.equipmentParameters.includes(option.sensorId)?'selected':'')}
      >
       <input
        type="checkbox"
        disabled={disabled}
        checked={value.equipmentParameters.includes(option.sensorId)}
        onChange={()=>onChange(toggleEquipmentParameter(value,option.sensorId))}
       />
       <span>{option.label}</span>
       <span className="muted">{option.unit}</span>
       {option.related&&<span className="badge">사건 관련</span>}
      </label>)}
     </div>}
  </Field>}
 </>;
}
