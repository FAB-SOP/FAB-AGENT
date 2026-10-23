import {api} from './client';
import type {ReportClassification} from './types';
/** 보고서 목록 한 행 — GET /api/reports */
export interface ReportListItem {
 reportId:string;
 incidentId:string;
 incidentDisplayId:string;
 processCode:string;
 version:number;
 status:string;
 title:string;
 contentRevision:number;
 createdAt:string;
 confirmedAt:string|null;
 detectedAt:string;
 classification:ReportClassification|null;
}
/** 페이지 한 장 — 전체 건수 없이 다음 페이지 여부만 */
export interface ReportSlice {items:ReportListItem[];page:number;size:number;hasNext:boolean}
export const REPORT_PAGE_SIZE=20;
/** 서버에서 공정 · 검색어 · 분류 · 파라미터 · 기간을 한 번에 조회한다. 빈 값 파라미터는 보내지 않는다 */
export function searchReports(params:Record<string,string>,page:number){
 const query=new URLSearchParams({...params,page:String(page),size:String(REPORT_PAGE_SIZE)});
 return api<ReportSlice>('/api/reports?'+query.toString());
}
