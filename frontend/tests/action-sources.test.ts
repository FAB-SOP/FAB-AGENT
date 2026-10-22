import {describe,it,expect} from 'vitest';
import {buildActionChoices,incidentStage,sourceKey} from '../src/domain/actionSources';
import {loadQuestionThread} from '../src/domain/questionThreads';

describe('linked action sources',()=>{
 it('keeps equal-looking hypotheses from different analysis versions distinct',()=>{
  const choices=buildActionChoices([{resultId:'r1',candidateCauses:[{causeId:'c1',hypothesis:'압력 확인'}]},{resultId:'r2',candidateCauses:[{causeId:'c2',hypothesis:'압력 확인'}]}],[]);
  expect(choices).toHaveLength(2);expect(choices[0].key).not.toBe(choices[1].key);
 });
 it('restores old linked snapshots and does not duplicate existing choices',()=>{
  const source={type:'CAUSE',refId:'r1',itemId:'c1',title:'압력 확인'};
  expect(buildActionChoices([],[],[{relatedSource:source}])[0].title).toBe('압력 확인');
  const choices=buildActionChoices([{resultId:'r1',candidateCauses:[{causeId:'c1',hypothesis:'압력 확인'}]}],[],[{relatedSource:source}]);
  expect(choices).toHaveLength(1);expect(sourceKey(source)).toBe(choices[0].key);expect(sourceKey(null)).toBe('');
 });
 it('includes independent next checks and proposal references without marking them performed',()=>{
  const choices=buildActionChoices([{resultId:'r1',nextActions:[{label:'센서 교정 이력 확인'}]}],[{approvalId:'a1',payload:{question:'정비 이력을 확인해 주세요.'}}]);
  expect(choices.map(choice=>choice.type)).toEqual(['NEXT_ACTION','PROPOSAL']);expect(choices[0].itemId).toBe('0');
 });
});
describe('incident stage labels',()=>{
 it.each([['BASIC','RUNNING','기본 분석 중'],['DEEP','RUNNING','추가 분석 중'],['DEEP','WAITING_APPROVAL','제안 검토 대기'],['RESUME','WAITING_REPLY','담당자 회신 대기'],['DEEP','SUCCEEDED','분석 완료']])('shows %s / %s as %s',(kind,status,label)=>{
  expect(incidentStage({status:'INVESTIGATING'},{kind,status}).label).toBe(label);
 });
 it('keeps closed and paused incident states above job states',()=>{
  expect(incidentStage({status:'CLOSED'},{status:'RUNNING'}).label).toBe('처리 종료');
  expect(incidentStage({status:'ON_HOLD'},{status:'SUCCEEDED'}).label).toBe('처리 보류');
 });
});
describe('question and answer continuity',()=>{
 it('follows approval and reply resume jobs back to one original question',async()=>{
  const data:Record<string,any>={q:{jobId:'q',question:'이전 공정 영향은?',status:'SUCCEEDED',resultRef:{type:'RESUME_JOB',id:'r1'}},r1:{jobId:'r1',status:'SUCCEEDED',resultRef:{type:'RESUME_JOB',id:'r2'}},r2:{jobId:'r2',status:'WAITING_REPLY'}};
  const thread=await loadQuestionThread('q',async id=>data[id]);
  expect(thread.rootJob.question).toBe('이전 공정 영향은?');expect(thread.latestJob.status).toBe('WAITING_REPLY');expect(thread.jobs.map(job=>job.jobId)).toEqual(['q','r1','r2']);
 });
 it('rejects cyclic resume references instead of hanging',async()=>{
  await expect(loadQuestionThread('q',async()=>({jobId:'q',resultRef:{type:'RESUME_JOB',id:'q'}}))).rejects.toThrow('순환');
 });
});
