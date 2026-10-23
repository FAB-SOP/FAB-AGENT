import {describe,it,expect} from 'vitest';
import {groupBySourceResult,reasonRequired} from '../src/components/WorkItems';

describe('work decision reasons',()=>{
 it('requires a reason only for a manager rejection',()=>{
  expect(reasonRequired('decline')).toBe(false);
  expect(reasonRequired('reject')).toBe(true);
  expect(reasonRequired('approve')).toBe(false);
  expect(reasonRequired('adopt')).toBe(false);
 });
 it('keeps every proposal under its source analysis, newest analysis first',()=>{
  const results=[{resultId:'r1',kind:'BASIC',createdAt:'2026-10-21T01:00:00Z'},{resultId:'r2',kind:'DEEP',createdAt:'2026-10-22T01:00:00Z'}];
  const items=[{taskId:'a',sourceResultId:'r1',status:'DECLINED'},{taskId:'b',sourceResultId:'r1',status:'PROPOSED'},{taskId:'c',sourceResultId:'r2',status:'READY'},{taskId:'d',sourceResultId:'old',status:'PROPOSED'}];
  const groups=groupBySourceResult(items,results);
  expect(groups.map(group=>group.resultId)).toEqual(['r2','r1','old']);
  expect(groups[1].items.map(item=>item.taskId)).toEqual(['a','b']);
  expect(groups[2].label).toBe('이전 분석');
 });
});
