import type {CauseCategory,Obj} from '../api/types';
/** 보고서 목록 필터 — 분류 · 장비 파라미터(Machine일 때) · 이상 발생 기간(사건 감지일, 한국 시간, 종료일 포함) */
export type ReportFilter={
 category:'ALL'|'UNCLASSIFIED'|CauseCategory;
 equipmentParameter:string;
 detectedFrom:string;
 detectedTo:string;
};
export const emptyReportFilter:ReportFilter={category:'ALL',equipmentParameter:'',detectedFrom:'',detectedTo:''};
/** 목록 한 행 — 보고서 요약과 그 사건의 이상 감지 시각 */
export type ReportRow={report:Obj;detectedAt?:string};
const seoulDate=new Intl.DateTimeFormat('en-CA',{timeZone:'Asia/Seoul',year:'numeric',month:'2-digit',day:'2-digit'});
/** ISO 시각 → 한국 시간 날짜 YYYY-MM-DD */
export const toSeoulDate=(value:string)=>seoulDate.format(new Date(value));
/** 시작일이 종료일보다 늦으면 잘못된 기간 — 한쪽만 지정하면 그쪽 경계만 쓴다 */
export const isValidDetectedRange=(filter:ReportFilter)=>
 !filter.detectedFrom||!filter.detectedTo||filter.detectedFrom<=filter.detectedTo;
function matchesCategory(report:Obj,filter:ReportFilter){
 const classification=report.classification;
 if(filter.category==='ALL')return true;
 if(filter.category==='UNCLASSIFIED')return !classification;
 if(classification?.category!==filter.category)return false;
 return filter.category!=='MACHINE'||!filter.equipmentParameter
  ||(classification.equipmentParameters||[]).includes(filter.equipmentParameter);
}
function matchesDetectedRange(detectedAt:string|undefined,filter:ReportFilter){
 if(!isValidDetectedRange(filter)||!filter.detectedFrom&&!filter.detectedTo)return true;
 if(!detectedAt)return false;
 const date=toSeoulDate(detectedAt);
 return (!filter.detectedFrom||date>=filter.detectedFrom)&&(!filter.detectedTo||date<=filter.detectedTo);
}
export function filterReports(rows:ReportRow[],filter:ReportFilter){
 return rows.filter(row=>matchesCategory(row.report,filter)&&matchesDetectedRange(row.detectedAt,filter));
}
