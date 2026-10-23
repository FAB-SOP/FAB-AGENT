import {describe,it,expect} from 'vitest';
import {answerText,conclusionChange,counterIds,nextCheck,nextStepView,stanceView} from '../src/domain/analysisMarks';

describe('agent v2 analysis marks',()=>{
 it('shows stance only when the server sent one',()=>{
  expect(stanceView('SUPPORTED')?.label).toBe('지지');
  expect(stanceView('REFUTED')?.label).toBe('반증');
  expect(stanceView('UNDETERMINED')?.label).toBe('판정 보류');
  expect(stanceView(undefined)).toBeNull();
  expect(stanceView('SOMETHING')).toBeNull();
 });
 it('reports a conclusion change only when changed is true',()=>{
  expect(conclusionChange({analysis:{conclusion:{changed:false,reason:'x'}}})).toBeNull();
  expect(conclusionChange({analysis:{}})).toBeNull();
  expect(conclusionChange({analysis:{conclusion:{changed:true,reason:'새 계측',fields:['topCause'],newEvidenceIds:['E-1']}}}))
   .toEqual({reason:'새 계측',fields:['topCause'],newEvidenceIds:['E-1']});
 });
 it('shows the next step only for known values',()=>{
  expect(nextStepView({analysis:{nextStep:'UPSTREAM_CHECK'}})?.label).toBe('앞 공정 확인');
  expect(nextStepView({analysis:{}})).toBeNull();
  expect(nextStepView({analysis:{nextStep:'SOMETHING'}})).toBeNull();
 });
 it('reads new and old field names',()=>{
  expect(answerText({answer:'전체 답',summary:'요약'})).toBe('전체 답');
  expect(answerText({summary:'요약'})).toBe('요약');
  expect(counterIds({conflictingEvidenceIds:['a']})).toEqual(['a']);
  expect(counterIds({counterEvidenceIds:['b']})).toEqual(['b']);
  expect(nextCheck({nextCheck:'n'})).toBe('n');
  expect(nextCheck({check:'c'})).toBe('c');
 });
});
