import {describe,it,expect} from 'vitest';
import {toSearchParams} from '../src/domain/reportSearch';
import {emptyReportFilter} from '../src/domain/reportFilters';

describe('report list search parameters',()=>{
 it('sends only the process when nothing is chosen',()=>{
  expect(toSearchParams('CMP','   ',emptyReportFilter)).toEqual({processCode:'CMP'});
 });
 it('sends a trimmed keyword and the chosen category',()=>{
  expect(toSearchParams('CMP',' PM3 ',{...emptyReportFilter,category:'HOLD'}))
   .toEqual({processCode:'CMP',q:'PM3',category:'HOLD'});
  expect(toSearchParams('CMP','',{...emptyReportFilter,category:'UNCLASSIFIED'}))
   .toEqual({processCode:'CMP',category:'UNCLASSIFIED'});
 });
 it('sends the equipment parameter only for machine',()=>{
  expect(toSearchParams('CMP','',{...emptyReportFilter,category:'MACHINE',equipmentParameter:'head_pressure'}))
   .toEqual({processCode:'CMP',category:'MACHINE',equipmentParameter:'head_pressure'});
  expect(toSearchParams('CMP','',{...emptyReportFilter,category:'METHOD',equipmentParameter:'head_pressure'}))
   .toEqual({processCode:'CMP',category:'METHOD'});
 });
 it('sends the detection period, one side or both, and drops an inverted period',()=>{
  expect(toSearchParams('CMP','',{...emptyReportFilter,detectedFrom:'2026-10-01',detectedTo:'2026-10-31'}))
   .toEqual({processCode:'CMP',detectedFrom:'2026-10-01',detectedTo:'2026-10-31'});
  expect(toSearchParams('CMP','',{...emptyReportFilter,detectedTo:'2026-10-31'}))
   .toEqual({processCode:'CMP',detectedTo:'2026-10-31'});
  expect(toSearchParams('CMP','',{...emptyReportFilter,detectedFrom:'2026-10-31',detectedTo:'2026-10-01'}))
   .toEqual({processCode:'CMP'});
 });
});
