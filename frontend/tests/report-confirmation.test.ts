import {describe,it,expect} from 'vitest';
import {ApiError} from '../src/api/client';
import {
 blockersFrom,canRetrySummary,describeBlocker,isSummaryInProgress,summaryStatusLabels,type ReportAiSummary,
} from '../src/domain/reportConfirmation';

const summary=(status:ReportAiSummary['status']):ReportAiSummary=>({status,lines:[],evidenceIds:[],generatedBy:null,updatedAt:null,error:null});

describe('report confirmation blockers',()=>{
 it('reads the remaining work from a CONFIRMATION_BLOCKED error only',()=>{
  const blocked=new ApiError(409,'CONFIRMATION_BLOCKED','정리할 일이 2건 남아 있습니다.',{blockers:[
   {kind:'REQUEST_UNANSWERED',id:'chk_1',label:'확인 요청(OPEN): 두께 확인'},
   {kind:'WORK_UNPERFORMED',id:'wrk_1',label:'유량 비교'},
   {kind:'UNKNOWN',id:'x',label:'모르는 종류'},
  ]});
  expect(blockersFrom(blocked).map(blocker=>blocker.id)).toEqual(['chk_1','wrk_1']);
  expect(blockersFrom(new ApiError(409,'REPORT_ALREADY_CONFIRMED','이미 확정',{blockers:[]}))).toEqual([]);
  expect(blockersFrom(new Error('network'))).toEqual([]);
 });
 it('tells where and how to clear each kind',()=>{
  expect(describeBlocker({kind:'REQUEST_UNANSWERED',id:'chk_1',label:'요청'},'inc_1'))
   .toMatchObject({path:'/check-requests',title:'회신이 오지 않은 확인 요청'});
  expect(describeBlocker({kind:'ANALYSIS_IN_PROGRESS',id:'job_1',label:'분석'},'inc_1').path).toBe('/incidents/inc_1');
  expect(describeBlocker({kind:'APPROVAL_PENDING',id:'apr_1',label:'제안'},'inc_1').path).toBe('/approvals');
  expect(describeBlocker({kind:'WORK_UNPERFORMED',id:'wrk_1',label:'업무'},'inc_1').howTo).toContain('미수행으로 닫아');
 });
});

describe('report AI summary',()=>{
 it('polls only while pending or running and offers retry only after failure',()=>{
  expect(isSummaryInProgress(summary('PENDING'))).toBe(true);
  expect(isSummaryInProgress(summary('RUNNING'))).toBe(true);
  expect(isSummaryInProgress(summary('READY'))).toBe(false);
  expect(isSummaryInProgress(null)).toBe(false);
  expect(canRetrySummary(summary('FAILED'),true)).toBe(true);
  expect(canRetrySummary(summary('FAILED'),false)).toBe(false);
  expect(canRetrySummary(summary('READY'),true)).toBe(false);
  expect(summaryStatusLabels.FAILED).toBe('요약 실패');
 });
});
