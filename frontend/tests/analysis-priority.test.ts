import {describe,it,expect} from 'vitest';
import {priorityView} from '../src/domain/analysisPriority';

describe('analysis priority view',()=>{
 it('shows server levels as 상·중·하 with the server reason',()=>{
  expect(priorityView({status:'COMPLETED',level:'HIGH',reason:'측정값 이상'})).toMatchObject({label:'상',bars:3,tone:'high',reason:'측정값 이상'});
  expect(priorityView({status:'COMPLETED',level:'MEDIUM',reason:null}).label).toBe('중');
  expect(priorityView({status:'COMPLETED',level:'LOW',reason:null}).label).toBe('하');
 });
 it('never turns missing, pending or failed judgments into 하',()=>{
  expect(priorityView(undefined).label).toBe('미평가');
  expect(priorityView(null).label).toBe('미평가');
  expect(priorityView({status:'NOT_EVALUATED',level:null,reason:null}).label).toBe('미평가');
  expect(priorityView({status:'PENDING',level:null,reason:null}).label).toBe('분석 중');
  expect(priorityView({status:'FAILED',level:null,reason:null}).label).toBe('판정 실패');
  expect(priorityView({status:'COMPLETED',level:null,reason:null}).label).toBe('미평가');
 });
});
