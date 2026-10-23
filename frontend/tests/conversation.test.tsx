import {describe,it,expect} from 'vitest';
import {renderToStaticMarkup} from 'react-dom/server';
import {conversationItems,conversationResults,runningJob,type ConversationPage} from '../src/domain/incidentConversation';
import {InvestigationConversation} from '../src/components/InvestigationConversation';
import {canManageInvestigation} from '../src/domain/permissions';
import {type Obj,type Session} from '../src/api/types';

const page=(items:Obj[],extra:Partial<ConversationPage>={}):ConversationPage=>({
 incidentId:'inc',items,nextCursor:null,jobs:[],runningJobs:[],waitingJobs:[],pendingApprovals:[],pendingWorkItems:[],
 latestCauseAnalysis:null,investigationResults:[],...extra,
});
const answer=(id:string,resultKind:string,summary:string,kind='BASIC')=>({
 entryId:'result:'+id,type:'AI_ANSWER',occurredAt:'2026-10-23T01:00:00Z',actor:{type:'AI'},job:{kind},
 data:{analysis:{resultId:id,resultKind,summary}},
});

describe('incident conversation API',()=>{
 it('puts older pages first, keeps server order and the newest copy of an entry',()=>{
  const newer=page([{entryId:'b',type:'QUESTION',text:'새 값'},{entryId:'c',type:'AI_ANSWER'}]);
  const older=page([{entryId:'a',type:'BASIC_REQUESTED'},{entryId:'b',type:'QUESTION',text:'옛 값'}]);
  const items=conversationItems([newer,older]);
  expect(items.map(item=>item.entryId)).toEqual(['a','b','c']);
  expect(items[1].text).toBe('새 값');
 });
 it('leaves work, request and action entries to the sections below',()=>{
  const items=conversationItems([page(['WORK_PROPOSED','WORK_EVENT','REQUEST_SENT','REQUEST_ACCEPTED','ACTION_RESULT','REPLY','QUESTION']
   .map(type=>({entryId:type,type})))]);
  expect(items.map(item=>item.type)).toEqual(['REPLY','QUESTION']);
 });
 it('attaches the original request to each reply',()=>{
  const items=conversationItems([page([
   {entryId:'s',type:'REQUEST_SENT',links:{requestId:'chk_1'},data:{request:{question:'제거량 확인 부탁',targetProcessCode:'ETCH'}}},
   {entryId:'r',type:'REPLY',links:{requestId:'chk_1'},text:'이상 없음'},
  ])]);
  expect(items).toHaveLength(1);
  expect(items[0].request.question).toBe('제거량 확인 부탁');
 });
 it('blocks new questions only while a job runs',()=>{
  expect(runningJob(page([],{waitingJobs:[{status:'WAITING_APPROVAL'}]}))).toBeUndefined();
  expect(runningJob(page([],{runningJobs:[{status:'RUNNING'}]}))?.status).toBe('RUNNING');
 });
 it('collects analyses for grouping suggestions, adding the latest cause analysis when it is on an older page',()=>{
  const items=[answer('r2','ANSWER','답')];
  expect(conversationResults(items,{resultId:'r1'}).map(result=>result.resultId)).toEqual(['r1','r2']);
 });
 it('folds older cause analyses and shows question answers and progress in place',()=>{
  const items=[
   answer('r1','CAUSE_ANALYSIS','예전 분석'),
   {entryId:'q',type:'QUESTION',occurredAt:'2026-10-23T02:00:00Z',actor:{type:'HUMAN',displayName:'박CMP'},text:'앞 공정은?',currentJob:{status:'RUNNING'}},
   answer('r2','CAUSE_ANALYSIS','최신 분석'),
  ];
  const html=renderToStaticMarkup(<InvestigationConversation items={items} latestCauseId="r2"/>);
  expect(html).toContain('<summary>이전 기본 분석 결과');
  expect(html.indexOf('예전 분석')).toBeLessThan(html.indexOf('앞 공정은?'));
  expect(html.indexOf('앞 공정은?')).toBeLessThan(html.indexOf('최신 분석'));
  expect(html).toContain('loading-spinner');
 });
 it('shows immediate feedback before the reanalysis appears',()=>{
  expect(renderToStaticMarkup(<InvestigationConversation items={[]} startingBasic/>)).toContain('재분석 요청 중…');
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
