import {isValidDetectedRange,type ReportFilter} from './reportFilters';
/**
 * 화면 필터 상태 → GET /api/reports 파라미터. 「전체」 · 빈 값은 보내지 않고, 장비 파라미터는 Machine일 때만,
 * 기간이 거꾸로면 기간을 빼고 보낸다(화면은 안내만 하고 필터를 적용하지 않는 기존 규칙과 같다).
 */
export function toSearchParams(process:string,keyword:string,filter:ReportFilter):Record<string,string>{
 const params:Record<string,string>={processCode:process};
 const trimmedKeyword=keyword.trim();
 if(trimmedKeyword)params.q=trimmedKeyword;
 if(filter.category!=='ALL')params.category=filter.category;
 if(filter.category==='MACHINE'&&filter.equipmentParameter)params.equipmentParameter=filter.equipmentParameter;
 if(isValidDetectedRange(filter)){
  if(filter.detectedFrom)params.detectedFrom=filter.detectedFrom;
  if(filter.detectedTo)params.detectedTo=filter.detectedTo;
 }
 return params;
}
