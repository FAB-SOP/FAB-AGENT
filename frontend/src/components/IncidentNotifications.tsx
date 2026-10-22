import {useEffect,useRef,useState} from 'react';
import {useQuery,useQueryClient} from '@tanstack/react-query';
import {Link} from 'react-router-dom';
import {X,TriangleAlert} from 'lucide-react';
import {api} from '../api/client';
import {newlyReceived,incidentTarget} from '../api/incident-notifications';
import type {Incident,Page} from '../api/types';
type StoredAlerts={seen:string[]|null;pending:Incident[]};
function restoreAlerts(storageKey:string):StoredAlerts|null{
 try{
  const stored=JSON.parse(sessionStorage.getItem(storageKey)||'null');
  return stored&&Array.isArray(stored.pending)&&(stored.seen===null||Array.isArray(stored.seen))?stored:null;
 }catch{
  return null;
 }
}
async function fetchAllIncidents(process:string):Promise<Incident[]>{
 const incidents:Incident[]=[];
 let cursor:string|null=null;
 const visited=new Set<string>();
 do{
  const cursorParam=cursor?'&cursor='+encodeURIComponent(cursor):'';
  const page:Page<Incident>=await api<Page<Incident>>(
   `/api/incidents?processCode=${encodeURIComponent(process)}&size=100${cursorParam}`,
  );
  incidents.push(...page.items);
  cursor=page.nextCursor;
  if(cursor&&visited.has(cursor))throw new Error('사건 목록 페이지를 이어서 조회하지 못했습니다.');
  if(cursor)visited.add(cursor);
 }while(cursor);
 return incidents;
}
function IncidentToast({incident,onDismiss}:{incident:Incident;onDismiss:(incidentId:string)=>void}){
 return <section className="incident-toast">
  <TriangleAlert size={23}/>
  <div>
   <strong>새 이상 발생 · {incident.lotId.replace('CURRENT-','')}</strong>
   <p>{incident.equipmentId} · {incident.title}</p>
   <Link className="text-button" to={incidentTarget(incident)}>해당 로트·설비 보기</Link>
   <Link className="text-button" to={'/incidents/'+incident.incidentId}>조사 열기</Link>
  </div>
  <button className="icon-button" aria-label={incident.displayId+' 알림 닫기'} onClick={()=>onDismiss(incident.incidentId)}>
   <X size={18}/>
  </button>
 </section>;
}
export function IncidentNotifications({process,userId}:{process:string;userId:string}){
 const storageKey=`fab:incident-alerts:${userId}:${process}`;
 const [restored]=useState(()=>restoreAlerts(storageKey));
 const seen=useRef<Set<string>|null>(restored?.seen?new Set<string>(restored.seen):null);
 const [pending,setPending]=useState<Incident[]>(restored?.pending||[]);
 const queryClient=useQueryClient();
 const incidentsQuery=useQuery({
  queryKey:['incident-notifications',userId,process],
  queryFn:()=>fetchAllIncidents(process),
  refetchInterval:5000,
  refetchIntervalInBackground:true,
 });
 useEffect(()=>{
  if(!incidentsQuery.data)return;
  const fresh=newlyReceived(seen.current,incidentsQuery.data);
  seen.current=new Set([...(seen.current||[]),...incidentsQuery.data.map(incident=>incident.incidentId)]);
  if(fresh.length){
   setPending(previous=>[...fresh,...previous]);
   queryClient.invalidateQueries({queryKey:['monitoring',process]});
   queryClient.invalidateQueries({queryKey:['dashboard',process]});
   queryClient.invalidateQueries({queryKey:['incidents',process]});
  }
 },[incidentsQuery.data,process,queryClient]);
 useEffect(()=>{
  try{
   sessionStorage.setItem(storageKey,JSON.stringify({seen:seen.current===null?null:[...seen.current],pending}));
  }catch{/* In-memory notifications remain available when browser storage is unavailable. */}
 },[pending,incidentsQuery.data,storageKey]);
 const dismiss=(incidentId:string)=>setPending(previous=>previous.filter(alert=>alert.incidentId!==incidentId));
 return <aside className="incident-notifications" aria-label="새 이상 알림">
  <div className="sr-only" role="status">{pending.length>0?`새 이상 알림 ${pending.length}건`:''}</div>
  {pending.map(incident=><IncidentToast key={incident.incidentId} incident={incident} onDismiss={dismiss}/>)}
  {incidentsQuery.isError&&<p className="notification-error" role="status">
   새 이상 알림을 확인하지 못했습니다. 연결되면 자동으로 다시 확인합니다.
  </p>}
 </aside>;
}
