import {describe,it,expect} from 'vitest';
import {renderToStaticMarkup} from 'react-dom/server';
import {MemoryRouter} from 'react-router-dom';
import {QueryClient,QueryClientProvider} from '@tanstack/react-query';
import {conversationEntries} from '../src/domain/conversation';
import {InvestigationConversation} from '../src/components/InvestigationConversation';
import {canManageInvestigation} from '../src/domain/permissions';
import {type Incident,type Session} from '../src/api/types';

describe('investigation conversation',()=>{
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

describe('accepting proposals inside answers',()=>{
 const incident={incidentId:'inc-1',status:'OPEN',version:3,processCode:'CMP'} as unknown as Incident;
 const render=(results:object[],entries:object[],items:object[],canManage=true)=>{
  const client=new QueryClient();client.setQueryData(['work-items','inc-1'],{items});
  return renderToStaticMarkup(<QueryClientProvider client={client}><MemoryRouter><InvestigationConversation entries={entries as never} threads={[]} results={results} work={{incident,canManage}}/></MemoryRouter></QueryClientProvider>);
 };
 const cause={causeId:'c1',hypothesis:'패드 마모',check:'패드 사용 시간 확인',action:'패드 교체 검토',supportingEvidence:['e1']};
 it('offers accept on each cause check and action that is not work yet',()=>{
  const html=render([{jobId:'b1',resultId:'r1',kind:'BASIC',createdAt:'2026-10-21T00:00:00Z',summary:'요약',candidateCauses:[cause],evidenceRefs:[{evidenceId:'e1'}]}],[{jobId:'b1',kind:'BASIC',createdAt:'2026-10-21T00:00:00Z'}],[]);
  expect(html.match(/제안 수락/g)).toHaveLength(2);expect(html).toContain('패드 교체 검토');
 });
 it('shows agent suggestions of a question answer with accept, and adopted work as its state',()=>{
  const items=[{taskId:'t1',sourceResultId:'r2',createdBy:'agent',route:'SELF_CHECK',status:'PROPOSED',title:'정비 이력 확인',reason:'교체 직후 발생'},{taskId:'t2',sourceResultId:'r2',sourceCauseId:'c1',createdBy:'u1',route:'SELF_CHECK',status:'READY',title:'패드 사용 시간 확인',reason:'패드 마모'}];
  const html=render([{jobId:'q1',resultId:'r2',kind:'DEEP',createdAt:'2026-10-21T01:00:00Z',summary:'답변',candidateCauses:[cause]}],[{jobId:'q1',kind:'DEEP',question:'원인은?',createdAt:'2026-10-21T01:00:00Z'}],items);
  expect(html).toContain('정비 이력 확인');expect(html).toContain('확인 진행 가능');expect(html.match(/제안 수락/g)).toHaveLength(2);
 });
 it('disables accept without write permission',()=>{
  const html=render([{jobId:'b1',resultId:'r1',kind:'BASIC',createdAt:'2026-10-21T00:00:00Z',summary:'요약',candidateCauses:[{...cause,action:undefined}]}],[{jobId:'b1',kind:'BASIC',createdAt:'2026-10-21T00:00:00Z'}],[],false);
  expect(html).toMatch(/disabled=""[^>]*>[^]*?제안 수락/);
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
