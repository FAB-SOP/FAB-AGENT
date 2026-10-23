import {describe,it,expect} from 'vitest';
import {selectionLabel,statisticsQuery,toggle} from '../src/api/causeStatistics';

describe('cause statistics',()=>{
 it('repeats months and categories, sorted and without duplicates',()=>{
  expect(statisticsQuery({year:2026,months:[10,3,10],categories:['MACHINE','HOLD'],page:0,size:20}))
   .toBe('?year=2026&months=3&months=10&categories=MACHINE&categories=HOLD&page=0&size=20');
  expect(statisticsQuery({})).toBe('');
 });
 it('describes the current selection',()=>{
  expect(selectionLabel(2026,[],[])).toBe('2026년 연간');
  expect(selectionLabel(2026,[4,3],['MACHINE','HOLD'])).toBe('2026년 3·4월 · Machine, 보류');
 });
 it('toggles a value in a list',()=>{
  expect(toggle([3,4],4)).toEqual([3]);
  expect(toggle([3],4)).toEqual([3,4]);
 });
});
