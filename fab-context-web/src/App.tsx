import {useState,lazy,Suspense} from 'react';
import {Link,Navigate,Route,Routes} from 'react-router-dom';
import {useAuth,Protected} from './auth';
import {Empty} from './components/ui';
const Dashboard=lazy(()=>import('./pages/Dashboard'));
import Login from './pages/Login';
import {WorkInbox} from './components/WorkItems';
import {Shell} from './components/Shell';
import {Knowledge} from './pages/Knowledge';
import {Approvals,IncidentDetail,IncidentList,ReportEditor,ReportList,Requests} from './pages/Workflow';
export default function App(){
 const [process,setProcess]=useState('CMP');
 return <Suspense fallback={<Empty>화면을 불러오는 중…</Empty>}>
  <Routes>
   <Route path="/login" element={<Login/>}/>
   <Route element={<Protected><Shell process={process} setProcess={setProcess}/></Protected>}>
    <Route index element={<Navigate to="/dashboard" replace/>}/>
    <Route path="dashboard" element={<ProcessPage process={process} kind="dashboard"/>}/>
    <Route path="incidents" element={<ProcessPage process={process} kind="incidents"/>}/>
    <Route path="incidents/:id" element={<IncidentDetail/>}/>
    <Route path="work-items" element={<WorkInbox/>}/>
    <Route path="check-requests" element={<Requests/>}/>
    <Route path="approvals" element={<Approvals/>}/>
    <Route path="reports" element={<ProcessPage process={process} kind="reports"/>}/>
    <Route path="reports/:id" element={<ReportEditor/>}/>
    <Route path="knowledge" element={<Knowledge/>}/>
    <Route path="*" element={<Empty>페이지를 찾을 수 없습니다. <Link to="/dashboard">대시보드로 돌아가기</Link></Empty>}/>
   </Route>
  </Routes>
 </Suspense>;
}
function ProcessPage({process,kind}:{process:string;kind:string}){
 const {session}=useAuth();
 const visibleProcess=session!.processScopes.includes(process)?process:session!.processScopes[0];
 if(kind==='dashboard')return <Dashboard process={visibleProcess}/>;
 return kind==='incidents'?<IncidentList process={visibleProcess}/>:<ReportList process={visibleProcess}/>;
}
