import {useState} from 'react';
import {useQuery} from '@tanstack/react-query';
import {useNavigate} from 'react-router-dom';
import {api} from '../api/client';
import type {Obj} from '../api/types';
import {useAuth} from '../auth';
export function DemoPersonaSelect(){
 const {session,switchPersona}=useAuth();const navigate=useNavigate();const [busy,setBusy]=useState(false);const [error,setError]=useState('');
 const q=useQuery({queryKey:['demo-personas'],queryFn:()=>api<Obj>('/api/auth/demo-personas'),retry:false,staleTime:60000});
 if(!q.data?.enabled)return null;
 return <div className="demo-persona-select"><label><span>데모 담당자</span><select aria-label="데모 담당자 전환" value={session?.user.username||''} disabled={busy} onChange={async event=>{const name=event.target.value;setBusy(true);setError('');try{await switchPersona(name);navigate('/dashboard',{replace:true})}catch(error){setError(error instanceof Error?error.message:'담당자 전환 실패')}finally{setBusy(false)}}}>{q.data.items.map((user:Obj)=><option key={user.userId} value={user.username}>{user.displayName} · {user.role==='MANAGER'?'전체 관리자':user.team}</option>)}</select></label>{error&&<span role="alert">{error}</span>}</div>;
}
