import {describe,it,expect} from 'vitest';
import {buildBlocks,conversationItems,flowView,pendingOutsidePages,runningJob,selectState,stepLabel,suggestionNumbers,type ConversationPage} from '../src/domain/incidentConversation';
import {canManageInvestigation} from '../src/domain/permissions';
import {type Obj,type Session} from '../src/api/types';

const page=(items:Obj[],extra:Partial<ConversationPage>={}):ConversationPage=>({
 incidentId:'inc',items,nextCursor:null,jobs:[],runningJobs:[],waitingJobs:[],pendingApprovals:[],pendingWorkItems:[],
 latestCauseAnalysis:null,investigationResults:[],...extra,
});
const at=(blockId:string,createdAt:string,flowId:string|null=null,blockType='BASIC')=>({blockId,blockType,createdAt,title:'',flowId});

describe('incident conversation pages',()=>{
 it('puts older pages first and keeps the newest copy of an entry',()=>{
  const newer=page([{entryId:'b',text:'새 값'},{entryId:'c'}]);
  const older=page([{entryId:'a'},{entryId:'b',text:'옛 값'}]);
  const items=conversationItems([newer,older]);
  expect(items.map(item=>item.entryId)).toEqual(['a','b','c']);
  expect(items[1].text).toBe('새 값');
 });
 it('blocks new questions only while a job runs',()=>{
  expect(runningJob(page([],{waitingJobs:[{status:'WAITING_APPROVAL'}]}))).toBeUndefined();
  expect(runningJob(page([],{runningJobs:[{status:'RUNNING'}]}))?.status).toBe('RUNNING');
 });
});

describe('investigation blocks',()=>{
 const basic=at('b1','2026-10-23T00:00:00Z');
 const items=[
  {entryId:'req',type:'BASIC_REQUESTED',placement:basic},
  {entryId:'ans',type:'AI_ANSWER',placement:basic,data:{analysis:{resultId:'r1'}}},
  {entryId:'w1',type:'WORK_PROPOSED',placement:at('b1','2026-10-23T00:00:00Z','work:1'),links:{sourceResultId:'r1'},data:{work:{createdBy:'agent',status:'PROPOSED'}}},
  {entryId:'w2',type:'WORK_PROPOSED',placement:at('b1','2026-10-23T00:00:00Z','work:2'),links:{sourceResultId:'r1'},data:{work:{createdBy:'agent',status:'PROPOSED'}}},
  {entryId:'q',type:'QUESTION',text:'앞 공정은?',placement:at('b2','2026-10-23T00:05:00Z',null,'QUESTION')},
  // 질문 뒤에 도착한 늦은 회신은 원래 블록·흐름에 남는다
  {entryId:'sent',type:'REQUEST_SENT',placement:at('b1','2026-10-23T00:00:00Z','work:2'),links:{requestId:'chk1'},data:{request:{status:'ACCEPTED'}}},
  {entryId:'rpl',type:'REPLY',placement:at('b1','2026-10-23T00:00:00Z','work:2'),links:{requestId:'chk1'},sourceRef:{type:'REPLY',id:'rp1'}},
  {entryId:'lost',type:'REPLY',placement:null,occurredAt:'2026-10-23T00:10:00Z'},
 ];
 const blocks=buildBlocks(items);
 it('groups by block in start order and keeps late replies in their original flow',()=>{
  expect(blocks.map(block=>block.blockId)).toEqual(['b1','b2','entry:lost']);
  expect(blocks[0].head?.entryId).toBe('req');
  expect(blocks[0].answers).toHaveLength(1);
  expect(blocks[0].flows.map(flow=>flow.flowId)).toEqual(['work:1','work:2']);
  const view=flowView(blocks[0].flows[1]);
  expect(view.requests[0].replies.map(reply=>reply.entryId)).toEqual(['rpl']);
  expect(view.history.map(stepLabel)).toEqual(['발송']);
  expect(blocks[1].head?.text).toBe('앞 공정은?');
 });
 it('numbers AI suggestions per answer',()=>{
  expect([...suggestionNumbers(blocks[0].flows).values()]).toEqual([1,2]);
 });
 it('labels each step by its own record type',()=>{
  expect(stepLabel({type:'APPROVAL_DECIDED',eventType:'REJECTED'})).toBe('승인 거절');
  expect(stepLabel({type:'WORK_EVENT',eventType:'WORK_DECIDED',text:'업무 결정 REJECT: x'})).toBe('관리자 반려');
  expect(stepLabel({type:'WORK_EVENT',eventType:'WORK_ADOPTED'})).toBe('채택');
  expect(stepLabel({type:'REPLY'})).toBe('');
 });
 it('lets a result be picked only until an analysis used it',()=>{
  expect(selectState({sourceRef:{},analysisUse:{successfulCount:0,activeRequestIds:[]}}).selectable).toBe(true);
  expect(selectState({sourceRef:{},analysisUse:{successfulCount:1,activeRequestIds:[]}}).selectable).toBe(false);
  expect(selectState({sourceRef:{},analysisUse:{successfulCount:0,activeRequestIds:['ar']}}).label).toContain('진행 중');
 });
 it('counts pending approvals and work that sit on pages not loaded yet',()=>{
  const latest=page([],{pendingApprovals:[{approvalId:'a1'},{approvalId:'a2'}],pendingWorkItems:[{taskId:'t1'}]});
  expect(pendingOutsidePages([{links:{approvalId:'a1'}},{data:{work:{taskId:'t1'}}}],latest)).toBe(1);
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
