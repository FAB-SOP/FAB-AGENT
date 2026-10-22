import {describe,it,expect} from 'vitest';
import {emptyReportFilter,filterReports,isValidDetectedRange,toSeoulDate,type ReportRow} from '../src/domain/reportFilters';

const machine=(id:string,parameters:string[],detectedAt:string):ReportRow=>
 ({report:{reportId:id,classification:{category:'MACHINE',equipmentParameters:parameters}},detectedAt});
const rows:ReportRow[]=[
 machine('m-oct01',['head_pressure'],'2026-10-01T03:00:00Z'),
 machine('m-oct15',['slurry_flow'],'2026-10-15T03:00:00Z'),
 machine('m-nov02',['head_pressure'],'2026-11-02T03:00:00Z'),
 {report:{reportId:'method',classification:{category:'METHOD',equipmentParameters:[]}},detectedAt:'2026-10-10T03:00:00Z'},
 {report:{reportId:'hold',classification:{category:'HOLD',equipmentParameters:[]}},detectedAt:'2026-10-11T03:00:00Z'},
 {report:{reportId:'legacy',classification:null},detectedAt:'2026-09-30T03:00:00Z'},
];
const ids=(list:ReportRow[])=>list.map(row=>row.report.reportId);

describe('report list filters',()=>{
 it('keeps every report when nothing is selected',()=>{
  expect(ids(filterReports(rows,emptyReportFilter))).toEqual(ids(rows));
 });
 it('filters by category, hold and reports without a classification',()=>{
  expect(ids(filterReports(rows,{...emptyReportFilter,category:'METHOD'}))).toEqual(['method']);
  expect(ids(filterReports(rows,{...emptyReportFilter,category:'HOLD'}))).toEqual(['hold']);
  expect(ids(filterReports(rows,{...emptyReportFilter,category:'UNCLASSIFIED'}))).toEqual(['legacy']);
 });
 it('narrows machine reports by equipment parameter',()=>{
  expect(ids(filterReports(rows,{...emptyReportFilter,category:'MACHINE'}))).toEqual(['m-oct01','m-oct15','m-nov02']);
  expect(ids(filterReports(rows,{...emptyReportFilter,category:'MACHINE',equipmentParameter:'head_pressure'})))
   .toEqual(['m-oct01','m-nov02']);
 });
 it('applies the anomaly detection period together with the category, end day included',()=>{
  const october={...emptyReportFilter,category:'MACHINE' as const,detectedFrom:'2026-10-01',detectedTo:'2026-10-31'};
  expect(ids(filterReports(rows,october))).toEqual(['m-oct01','m-oct15']);
  expect(ids(filterReports(rows,{...emptyReportFilter,detectedFrom:'2026-10-15'}))).toEqual(['m-oct15','m-nov02']);
  expect(ids(filterReports(rows,{...emptyReportFilter,detectedTo:'2026-10-01'}))).toEqual(['m-oct01','legacy']);
 });
 it('uses the Korean calendar day and ignores an inverted period',()=>{
  // 2026-10-31T16:00Z는 한국 시간 11월 1일 01시
  expect(toSeoulDate('2026-10-31T16:00:00Z')).toBe('2026-11-01');
  const inverted={...emptyReportFilter,detectedFrom:'2026-10-31',detectedTo:'2026-10-01'};
  expect(isValidDetectedRange(inverted)).toBe(false);
  expect(ids(filterReports(rows,inverted))).toEqual(ids(rows));
 });
});
