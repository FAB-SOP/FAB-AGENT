import {describe,it,expect} from 'vitest';
import {mergeNotifications,notificationPath,type UserNotification} from '../src/api/notifications';

const n=(id:string,eventId:string,extra:Partial<UserNotification>={}):UserNotification=>({
 notificationId:id,eventId,type:'ANALYSIS_SUCCEEDED',title:'',target:{type:'JOB',id:'job_1'},parent:null,
 incidentId:'inc_1',incidentDisplayId:'EV-1',actorUserId:null,createdAt:'',readAt:null,read:false,...extra,
});

describe('user notifications',()=>{
 it('routes each target to its screen',()=>{
  expect(notificationPath(n('a','1'))).toBe('/incidents/inc_1');
  expect(notificationPath(n('a','1',{target:{type:'CHECK_REQUEST',id:'chk_1'}}))).toBe('/check-requests?request=chk_1');
  expect(notificationPath(n('a','1',{target:{type:'REPLY',id:'rpl_1'},parent:{type:'CHECK_REQUEST',id:'chk_1'}}))).toBe('/check-requests?request=chk_1');
  expect(notificationPath(n('a','1',{type:'WORK_APPROVAL_REQUESTED',target:{type:'WORK_ITEM',id:'task_1'}}))).toBe('/approvals');
  expect(notificationPath(n('a','1',{type:'WORK_APPROVED',target:{type:'WORK_ITEM',id:'task_1'}}))).toBe('/incidents/inc_1?tab=actions');
  expect(notificationPath(n('a','1',{target:{type:'INCIDENT',id:'inc_2'}}))).toBe('/incidents/inc_2');
 });
 it('merges list and stream without duplicates, newest first',()=>{
  const merged=mergeNotifications([n('a','1'),n('b','2')],[n('b','2',{read:true}),n('c','3')]);
  expect(merged.map(item=>item.notificationId)).toEqual(['c','b','a']);
  expect(merged[1].read).toBe(true);
 });
});
