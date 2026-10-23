/** 사용자별 통합 알림 (docs/user-notifications-api.md) */
export interface NotificationRef {type:string;id:string}
export interface UserNotification {
 notificationId:string;
 eventId:string;
 type:string;
 title:string;
 target:NotificationRef;
 parent:NotificationRef|null;
 incidentId:string|null;
 incidentDisplayId:string|null;
 actorUserId:string|null;
 createdAt:string;
 readAt:string|null;
 read:boolean;
}
const enc=encodeURIComponent;
/** 알림을 누르면 갈 화면 — target·parent 종류로만 정한다 */
export function notificationPath(n:Pick<UserNotification,'type'|'target'|'parent'|'incidentId'>):string{
 const incident=n.incidentId?'/incidents/'+enc(n.incidentId):'/incidents';
 switch(n.target.type){
  case 'INCIDENT':return '/incidents/'+enc(n.target.id);
  case 'CHECK_REQUEST':return '/check-requests?request='+enc(n.target.id);
  case 'REPLY':return n.parent?.type==='CHECK_REQUEST'?'/check-requests?request='+enc(n.parent.id):incident;
  case 'WORK_ITEM':return n.type==='WORK_APPROVAL_REQUESTED'?'/approvals':incident+'?tab=actions';
  default:return incident;
 }
}
/** 목록과 SSE로 받은 알림을 notificationId로 중복 없이 합치고 최신(eventId 큰 것)부터 */
export function mergeNotifications(current:UserNotification[],incoming:UserNotification[]):UserNotification[]{
 const byId=new Map(current.map(item=>[item.notificationId,item]));
 for(const item of incoming)byId.set(item.notificationId,item);
 return [...byId.values()].sort((a,b)=>Number(b.eventId)-Number(a.eventId));
}
/** 알림 종류별로 다시 불러올 화면 데이터(react-query 키 앞부분) */
export function relatedQueryKeys(type:string):string[]{
 if(type.startsWith('CHECK_REQUEST'))return ['requests','request','incident-requests','my-work','investigation-results'];
 if(type.startsWith('WORK_'))return ['work-approvals','my-work','work-items'];
 if(type.startsWith('ANALYSIS_'))return ['incident','analysis','analysis-source','investigation-results'];
 if(type==='INCIDENT_CREATED')return ['incidents','dashboard'];
 return [];
}
