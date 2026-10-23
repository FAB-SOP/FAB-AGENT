import {useEffect,useState} from 'react';
import {useNavigate} from 'react-router-dom';
import {useQuery,useQueryClient} from '@tanstack/react-query';
import {Bell} from 'lucide-react';
import {API_MODE,api,write} from '../api/client';
import {when} from '../api/types';
import {mergeNotifications,notificationPath,relatedQueryKeys,type UserNotification} from '../api/notifications';

const LIST_KEY=['notifications','list'];
const COUNT_KEY=['notifications','count'];
type List={items:UserNotification[]};
/** 상단 종 — 사용자별 SSE 하나로 새 알림·읽음을 받고, 정기 폴링은 하지 않는다. 사용자가 바뀌면 key로 다시 마운트한다 */
export function NotificationBell(){
 const queryClient=useQueryClient();
 const navigate=useNavigate();
 const [open,setOpen]=useState(false);
 const list=useQuery({queryKey:LIST_KEY,queryFn:()=>api<List>('/api/notifications?size=20')});
 const count=useQuery({queryKey:COUNT_KEY,queryFn:()=>api<{unreadCount:number}>('/api/notifications/unread-count')});
 useEffect(()=>{
  if(API_MODE==='mock')return;
  // 브라우저가 끊기면 Last-Event-ID로 자동 재접속한다
  const source=new EventSource('/api/notifications/stream',{withCredentials:true});
  const setCount=(unreadCount:number)=>queryClient.setQueryData(COUNT_KEY,{unreadCount});
  const refetchAll=()=>queryClient.invalidateQueries({queryKey:['notifications']});
  source.addEventListener('ready',refetchAll);
  source.addEventListener('notification.resync',refetchAll);
  source.addEventListener('notification',event=>{
   const data=JSON.parse((event as MessageEvent).data);
   queryClient.setQueryData<List>(LIST_KEY,old=>({items:mergeNotifications(old?.items||[],[data.notification])}));
   setCount(data.unreadCount);
   const keys=relatedQueryKeys(data.notification.type);
   if(keys.length)queryClient.invalidateQueries({predicate:query=>keys.includes(String(query.queryKey[0]))});
  });
  source.addEventListener('notification.read',event=>{
   const data=JSON.parse((event as MessageEvent).data);
   queryClient.setQueryData<List>(LIST_KEY,old=>old&&{items:old.items.map(item=>item.notificationId===data.notificationId?{...item,read:true,readAt:data.readAt}:item)});
   setCount(data.unreadCount);
  });
  return()=>source.close();
 },[queryClient]);
 const openNotification=async(item:UserNotification)=>{
  setOpen(false);
  if(!item.read){
   try{await write(`/api/notifications/${encodeURIComponent(item.notificationId)}/read`,{});}catch{/* 읽음 실패해도 이동은 한다 */}
   queryClient.invalidateQueries({queryKey:['notifications']});
  }
  navigate(notificationPath(item));
 };
 const unread=count.data?.unreadCount||0;
 const items=list.data?.items||[];
 return <div className="notification-bell">
  <button className="icon-button" aria-label={`알림 ${unread}건 안 읽음`} aria-expanded={open} onClick={()=>setOpen(value=>!value)}>
   <Bell size={20}/>{unread>0&&<span className="notification-count">{unread>99?'99+':unread}</span>}
  </button>
  {open&&<>
   <div className="notification-backdrop" onClick={()=>setOpen(false)}/>
   <div className="notification-panel" role="dialog" aria-label="알림">
    <div className="notification-head"><strong>알림</strong><span className="small muted">안 읽음 {unread}건</span></div>
    {items.length?items.map(item=><button key={item.notificationId} className={'notification-item'+(item.read?'':' unread')} onClick={()=>openNotification(item)}>
     <span className="notification-title">{item.title}</span>
     <span className="small muted">{item.incidentDisplayId&&item.incidentDisplayId+' · '}{when(item.createdAt)}</span>
    </button>):<p className="small muted notification-empty">새 알림이 없습니다.</p>}
   </div>
  </>}
 </div>;
}
