/** 원인 통계 (docs/cause-statistics-api.md) — 숫자는 서버가 센다. 프론트는 받은 집계를 그린다 */
export const CAUSE_CATEGORIES=['MACHINE','MATERIAL','METHOD','MAN','HOLD'] as const;
export const causeLabels:Record<string,string>={MACHINE:'Machine',MATERIAL:'Material',METHOD:'Method',MAN:'Man',HOLD:'보류'};
/** 4M마다 고정 색 — 우선순위 상·중·하(빨강·주황·노랑)와 겹치지 않게 */
export const causeColors:Record<string,string>={MACHINE:'#2a78d6',MATERIAL:'#1baf7a',METHOD:'#7a5dc7',MAN:'#c9558b',HOLD:'#9aa3a8'};
export interface CauseStatistics {
 year:number;
 months:number[];
 availableYears:number[];
 annualTotalCount:number;
 monthly:{month:number;count:number}[];
 totalCount:number;
 classifiedCount:number;
 unclassifiedCount:number;
 categoryCounts:{category:string;count:number}[];
}
export interface CauseReport {
 reportId:string;
 incidentId:string;
 incidentDisplayId:string;
 title:string;
 processCode:string;
 equipmentId:string;
 detectedAt:string;
 createdAt:string;
 confirmedAt:string|null;
 version:number;
 classification:{category:string;equipmentParameters?:string[]}|null;
}
export interface CauseReportPage {items:CauseReport[];page:number;size:number;totalElements:number;totalPages:number}
/** 같은 이름의 값을 반복 전달한다 (months=9&months=10) */
export function statisticsQuery(filter:{year?:number;months?:number[];categories?:string[];page?:number;size?:number}):string{
 const params=new URLSearchParams();
 if(filter.year)params.set('year',String(filter.year));
 [...new Set(filter.months||[])].sort((a,b)=>a-b).forEach(month=>params.append('months',String(month)));
 (filter.categories||[]).forEach(category=>params.append('categories',category));
 if(filter.page!==undefined)params.set('page',String(filter.page));
 if(filter.size!==undefined)params.set('size',String(filter.size));
 const query=params.toString();
 return query?'?'+query:'';
}
/** 화면 위 선택 상태 문구 — 예: 2026년 3·4월 · Machine, 보류 */
export function selectionLabel(year:number,months:number[],categories:string[]):string{
 const period=months.length?[...months].sort((a,b)=>a-b).join('·')+'월':'연간';
 const causes=categories.length?' · '+categories.map(category=>causeLabels[category]||category).join(', '):'';
 return `${year}년 ${period}${causes}`;
}
export const toggle=<T,>(list:T[],value:T)=>list.includes(value)?list.filter(item=>item!==value):[...list,value];
