import {Link,NavLink,Outlet,useLocation} from 'react-router-dom';
import {
 ListTodo,Activity,BookOpen,ChartPie,CheckCheck,FileText,Layers3,LayoutDashboard,LogOut,MessagesSquare,ShieldCheck,
} from 'lucide-react';
import {useAuth} from '../auth';
import {API_MODE} from '../api/client';
import {DemoPersonaSelect} from './DemoPersonaSelect';
import {IncidentNotifications} from './IncidentNotifications';
import {NotificationBell} from './NotificationBell';
import {canViewCauseStatistics} from '../domain/permissions';
const NAVIGATION=[
 ['/dashboard','공정 대시보드',LayoutDashboard],
 ['/incidents','이상 조사',Activity],
 ['/work-items','내 할 일',ListTodo],
 ['/check-requests','확인·정비 요청',MessagesSquare],
 ['/approvals','제안 검토·승인',CheckCheck],
 ['/reports','조사 보고서',FileText],
 ['/knowledge','참고 문서',BookOpen],
] as const;
/** 관리자·설비정비팀에게만 보이는 메뉴 — 서버도 같은 기준으로 막는다 */
const STATISTICS_NAVIGATION=[['/cause-statistics','원인 통계',ChartPie]] as const;
function Sidebar({visibleProcess,isMock,canViewStatistics}:{visibleProcess:string;isMock:boolean;canViewStatistics:boolean}){
 return <aside className="sidebar">
  <Link to="/dashboard" className="brand">
   <span className="brand-mark"><Layers3 size={24}/></span>
   <span>FAB<span className="brand-sub">CONTEXT</span></span>
  </Link>
  <div className="workspace-name">
   <span className="small">공정 조사 워크스페이스</span>
   <strong>라인 A · {visibleProcess}</strong>
  </div>
  <nav aria-label="주 메뉴">
   {[...NAVIGATION,...(canViewStatistics?STATISTICS_NAVIGATION:[])].map(([href,name,Icon])=><NavLink key={href} to={href}><Icon size={20}/><span>{name}</span></NavLink>)}
  </nav>
  <div className="sidebar-bottom">
   <div className="workspace-health">
    <ShieldCheck size={19}/>
    <span>{isMock?'확정 합성 데이터':'담당 공정 연결됨'}</span>
   </div>
   <p className="small">{isMock?'모의 API · 팀 시연용':'업무 시스템 연결'}</p>
   <p className="small">FAB CONTEXT / MVP</p>
  </div>
 </aside>;
}
function Topbar({currentPageName,isMock,scopes,visibleProcess,onProcessChange,displayName,roleLabel,onLogout,userKey}:{
 currentPageName:string;
 isMock:boolean;
 scopes:string[];
 visibleProcess:string;
 onProcessChange:(process:string)=>void;
 displayName:string;
 roleLabel:string;
 onLogout:()=>void;
 userKey:string;
}){
 return <header className="topbar">
  <div className="breadcrumb">FAB CONTEXT <span>/</span> {currentPageName}</div>
  <div className="topbar-actions">
   <span className={'connection-mode '+(isMock?'demo':'')}><i/>{isMock?'모의 API 연결':'연결됨'}</span>
   <DemoPersonaSelect/>
   <label className="process-select">
    <span className="sr-only">담당 공정</span>
    <select value={visibleProcess} onChange={event=>onProcessChange(event.target.value)}>
     {scopes.map(scope=><option key={scope}>{scope}</option>)}
    </select>
   </label>
   <NotificationBell key={userKey}/>
   <span className="user-avatar">{displayName.slice(0,1)}</span>
   <div className="user-name">
    <strong>{displayName}</strong>
    <span>{roleLabel}</span>
   </div>
   <button className="icon-button" aria-label="로그아웃" onClick={onLogout}><LogOut size={19}/></button>
  </div>
 </header>;
}
export function Shell({process,setProcess}:{process:string;setProcess:(process:string)=>void}){
 const {session,logout}=useAuth();
 const scopes=session!.processScopes;
 const visibleProcess=scopes.includes(process)?process:scopes[0];
 const location=useLocation();
 const isMock=API_MODE==='mock';
 const currentPageName=[...NAVIGATION,...STATISTICS_NAVIGATION].find(([href])=>location.pathname.startsWith(href))?.[1]||'공정 조사';
 const roleLabel=session!.role==='MANAGER'
  ?'운영 관리자'
  :session!.investigationProcessScopes?.length===0?session!.user.team+' · 조회 전용':visibleProcess+' 공정 담당자';
 return <div className="app-shell">
  <a className="skip-link" href="#main">본문으로 이동</a>
  <Sidebar visibleProcess={visibleProcess} isMock={isMock} canViewStatistics={canViewCauseStatistics(session)}/>
  <div className="workspace">
   <Topbar
    currentPageName={currentPageName}
    isMock={isMock}
    scopes={scopes}
    visibleProcess={visibleProcess}
    onProcessChange={setProcess}
    displayName={session!.user.displayName}
    userKey={session!.user.userId}
    roleLabel={roleLabel}
    onLogout={()=>logout().catch(error=>alert(error.message))}
   />
   <main id="main" key={session!.user.userId}><Outlet/></main>
   <IncidentNotifications key={session!.user.userId+':'+visibleProcess} userId={session!.user.userId} process={visibleProcess}/>
   <footer className="workspace-footer">
    <span></span>
    <span>{isMock?'시나리오 데이터 v2 · 모의 분석':'실제 API 응답 기준'} · KST</span>
   </footer>
  </div>
 </div>;
}
