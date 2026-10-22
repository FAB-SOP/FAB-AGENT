import {describe,it,expect} from 'vitest';
import {renderToStaticMarkup} from 'react-dom/server';
import {MemoryRouter} from 'react-router-dom';
import {conversationEntries} from '../src/domain/conversation';
import {InvestigationConversation} from '../src/components/InvestigationConversation';
import {canManageInvestigation} from '../src/domain/permissions';
import {type Session} from '../src/api/types';

describe('investigation conversation',()=>{
 it('shows a follow-up analysis that has no question record, without duplicating asked questions',()=>{
  const timeline=[{type:'QUESTION_ASKED',ref:{type:'JOB',id:'job_q'},message:'추가 조사 질문: 앞 공정은?',at:'2026-10-22T02:00:00Z'}];
  const history=[{kind:'BASIC',jobId:'job_b',createdAt:'2026-10-22T01:00:00Z'},{kind:'DEEP',jobId:'job_q',createdAt:'2026-10-22T02:01:00Z'},{kind:'DEEP',jobId:'job_f',createdAt:'2026-10-22T03:00:00Z'}];
  const entries=conversationEntries(timeline,history);
  expect(entries.map(entry=>entry.jobId)).toEqual(['job_b','job_q','job_f']);
  expect(entries[2]).toMatchObject({kind:'DEEP',followup:true,question:'선택한 결과를 반영한 추가 분석'});
  expect(entries[1].question).toBe('앞 공정은?');
 });
 const timeline=[{at:'2026-10-21T01:00:00Z',type:'QUESTION_ASKED',message:'추가 조사 질문: 이전 공정 영향은?',ref:{type:'JOB',id:'q1'}},{at:'2026-10-21T02:00:00Z',type:'ANALYSIS_RERUN',ref:{type:'JOB',id:'b2'}}];
 const history=[{jobId:'b1',resultId:'r1',kind:'BASIC',createdAt:'2026-10-21T00:00:00Z',summary:'최초 분석 결과'},{jobId:'q1',resultId:'r2',kind:'DEEP',createdAt:'2026-10-21T01:01:00Z',summary:'이전 공정 확인 답변'}];
 it('orders the original analysis, each question and later reanalysis by request time',()=>{
  const entries=conversationEntries(timeline,history,{jobId:'b2',kind:'BASIC',status:'RUNNING'});
  expect(entries.map(entry=>entry.jobId)).toEqual(['b1','q1','b2']);
  const html=renderToStaticMarkup(<MemoryRouter><InvestigationConversation entries={entries} threads={[undefined,{rootJob:{jobId:'q1'},latestJob:{jobId:'q1',status:'SUCCEEDED'},jobs:[{jobId:'q1'}]},undefined]} results={history} activeJob={{jobId:'b2',kind:'BASIC',status:'RUNNING'}}/></MemoryRouter>);
  expect(html.indexOf('최초 분석 결과')).toBeLessThan(html.indexOf('이전 공정 영향은?'));
  expect(html.indexOf('이전 공정 영향은?')).toBeLessThan(html.indexOf('이전 공정 확인 답변'));
  expect(html.indexOf('이전 공정 확인 답변')).toBeLessThan(html.indexOf('재분석 중…'));
  expect(html).toContain('role="status"');expect(html).toContain('loading-spinner');
 });
 it('does not duplicate the same reanalysis after the result arrives and removes its loading state',()=>{
  const result={jobId:'b2',resultId:'r3',kind:'BASIC',createdAt:'2026-10-21T02:01:00Z',summary:'새 분석 결과'};
  const entries=conversationEntries(timeline,[...history,result]);
  expect(entries.filter(entry=>entry.jobId==='b2')).toHaveLength(1);
  const html=renderToStaticMarkup(<MemoryRouter><InvestigationConversation entries={[entries[2]]} threads={[{rootJob:{jobId:'b2'},latestJob:{jobId:'b2',status:'SUCCEEDED'},jobs:[{jobId:'b2'}]}]} results={[result]}/></MemoryRouter>);
  expect(html).toContain('새 분석 결과');expect(html).not.toContain('재분석 중…');expect(html).not.toContain('loading-spinner');
 });
 it('shows immediate feedback before the reanalysis job has been fetched',()=>{
  const html=renderToStaticMarkup(<InvestigationConversation entries={[]} threads={[]} results={[]} startingBasic/>);
  expect(html).toContain('재분석 요청 중…');
 });
});

describe('investigation write controls',()=>{
 it('uses the server write scopes, not the wider read scopes',()=>{
  const reader={processScopes:['CVD','ETCH','CMP'],investigationProcessScopes:[]} as unknown as Session;
  expect(canManageInvestigation(reader,'CMP')).toBe(false);
  expect(canManageInvestigation({...reader,investigationProcessScopes:['CMP']},'CMP')).toBe(true);
  expect(canManageInvestigation({...reader,investigationProcessScopes:['CMP']},'ETCH')).toBe(false);
  expect(canManageInvestigation({...reader,investigationProcessScopes:undefined},'CMP')).toBe(false);
 });
});
