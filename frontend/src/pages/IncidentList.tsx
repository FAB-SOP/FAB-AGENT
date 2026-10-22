import {useState} from 'react';
import {Link,useNavigate} from 'react-router-dom';
import {useQuery} from '@tanstack/react-query';
import {ArrowUpRight} from 'lucide-react';
import {api} from '../api/client';
import {labels,when,type Incident,type Page} from '../api/types';
import {Badge,Empty,ErrorMessage,PageHeading,Panel} from '../components/ui';
import {incidentStage} from '../domain/actionSources';
export function IncidentList({process}:{process:string}){
 const [text,setText]=useState('');
 const [status,setStatus]=useState('');
 const incidents=useQuery({
  queryKey:['incidents',process,text,status],
  queryFn:()=>api<Page<Incident>>(`/api/incidents?processCode=${process}&size=100&q=${encodeURIComponent(text)}${status?'&status='+status:''}`),
  refetchInterval:10000,
 });
 return <>
  <PageHeading kicker="INVESTIGATIONS" title="이상 조사" description="관측된 이상부터 확인 요청, 실제 조치와 결과까지 하나의 사건으로 추적합니다."/>
  <div className="filter-row">
   <input aria-label="사건 검색" placeholder="사건명·로트·사건 번호 검색" value={text} onChange={event=>setText(event.target.value)}/>
   <select aria-label="사건 상태" value={status} onChange={event=>setStatus(event.target.value)}>
    <option value="">모든 상태</option>
    {['OPEN','INVESTIGATING','WAITING_REPLY','ON_HOLD','CLOSED'].map(code=><option key={code} value={code}>{labels[code]}</option>)}
   </select>
  </div>
  <Panel>{incidents.isError?<ErrorMessage error={incidents.error}/>:<IncidentTable items={incidents.data?.items||[]}/>}</Panel>
 </>;
}
function IncidentRow({incident,onOpen}:{incident:Incident;onOpen:(incidentId:string)=>void}){
 return <tr
  className="incident-row"
  tabIndex={0}
  aria-label={incident.displayId+' · '+incident.title+' 조사 열기'}
  onClick={()=>onOpen(incident.incidentId)}
  onKeyDown={event=>{
   if(event.target===event.currentTarget&&(event.key==='Enter'||event.key===' ')){
    event.preventDefault();
    onOpen(incident.incidentId);
   }
  }}
 >
  <td>
   <Link className="table-link" to={'/incidents/'+incident.incidentId}>{incident.title}</Link>
   <span className="table-sub">{incident.displayId} · {incident.processCode}</span>
  </td>
  <td>{incident.lotId.replace('CURRENT-','')}<span className="table-sub">{incident.equipmentId}</span></td>
  <td><Badge value={incidentStage(incident).code}>{incidentStage(incident).label}</Badge></td>
  <td>{when(incident.detectedAt)}</td>
  <td>
   <Link aria-label={incident.displayId+' 조사 열기'} className="icon-button" to={'/incidents/'+incident.incidentId}><ArrowUpRight size={20}/></Link>
  </td>
 </tr>;
}
function IncidentTable({items}:{items:Incident[]}){
 const navigate=useNavigate();
 if(!items.length)return <Empty>이 범위에 등록된 이상 사건이 없습니다.</Empty>;
 const openIncident=(incidentId:string)=>navigate('/incidents/'+incidentId);
 return <div className="table-wrap">
  <table>
   <thead>
    <tr>
     <th>사건 / 관측 내용</th>
     <th>로트 · 설비</th>
     <th>조사 상태</th>
     <th>발견 시각</th>
     <th/>
    </tr>
   </thead>
   <tbody>
    {items.map(incident=><IncidentRow key={incident.incidentId} incident={incident} onOpen={openIncident}/>)}
   </tbody>
  </table>
 </div>;
}
