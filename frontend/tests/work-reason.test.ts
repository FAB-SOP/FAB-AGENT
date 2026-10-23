import {describe,it,expect} from 'vitest';
import {classifyAnalysisWork,groupBySourceResult,reasonRequired} from '../src/components/WorkItems';

describe('work decision reasons',()=>{
 it('requires a reason for a manager rejection or unperformed closure',()=>{
  expect(reasonRequired('decline')).toBe(false);
  expect(reasonRequired('reject')).toBe(true);
  expect(reasonRequired('approve')).toBe(false);
  expect(reasonRequired('adopt')).toBe(false);
  expect(reasonRequired('unperformed')).toBe(true);
 });
 it('keeps every proposal under its source analysis, newest analysis first',()=>{
  const results=[{resultId:'r1',kind:'BASIC',createdAt:'2026-10-21T01:00:00Z'},{resultId:'r2',kind:'DEEP',createdAt:'2026-10-22T01:00:00Z'}];
  const items=[{taskId:'a',sourceResultId:'r1',status:'DECLINED'},{taskId:'b',sourceResultId:'r1',status:'PROPOSED'},{taskId:'c',sourceResultId:'r2',status:'READY'},{taskId:'d',sourceResultId:'old',status:'PROPOSED'}];
  const groups=groupBySourceResult(items,results);
  expect(groups.map(group=>group.resultId)).toEqual(['r2','r1','old']);
  expect(groups[1].items.map(item=>item.taskId)).toEqual(['a','b']);
  expect(groups[2].label).toBe('이전 분석');
 });
 it('numbers AI suggestions per analysis and keeps adopted work separate',()=>{
  const groups=[{resultId:'r1',label:'기본 분석',items:[
   {taskId:'a1',createdBy:'agent',status:'PROPOSED',route:'SELF_CHECK',createdAt:'2026-10-23T01:00:00Z'},
   {taskId:'a2',createdBy:'agent',status:'READY',route:'SELF_CHECK',createdAt:'2026-10-23T01:00:01Z'},
   {taskId:'a3',createdBy:'agent',status:'DECLINED',route:'TEAM_REQUEST',createdAt:'2026-10-23T01:00:02Z'},
   {taskId:'h1',createdBy:'USR-CMP-01',status:'PROPOSED',route:'SELF_CHECK',createdAt:'2026-10-23T02:00:00Z'},
   {taskId:'h2',createdBy:'USR-CMP-01',status:'COMPLETED',route:'TEAM_REQUEST',createdAt:'2026-10-23T02:00:00Z'},
  ]}];
  const view=classifyAnalysisWork(groups);
  expect(view.aiTotal).toBe(3);
  expect(view.aiWaiting).toBe(1);
  expect(view.aiGroups[0].items.map(entry=>[entry.item.taskId,entry.number])).toEqual([['a1',1],['a2',2],['a3',3]]);
  expect(view.direct.map(item=>item.taskId)).toEqual(['h1']);
  expect(view.mine.map(item=>item.taskId)).toEqual(['a2']);
  expect(view.team.map(item=>item.taskId)).toEqual(['h2']);
  expect(view.numberOf({taskId:'a2'})).toBe(2);
 });
});
