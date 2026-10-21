import {createContext,useContext,useEffect,type ReactNode} from 'react';
import {useQuery,useQueryClient} from '@tanstack/react-query';
import {Navigate,useLocation} from 'react-router-dom';
import {api,resetCsrf,write} from './api/client';
import type {Session} from './api/types';
type AuthValue={session:Session|null|undefined;loading:boolean;login:(username:string,password:string)=>Promise<void>;switchPersona:(username:string)=>Promise<void>;logout:()=>Promise<void>};
const Auth=createContext<AuthValue>(null!);
export function AuthProvider({children}:{children:ReactNode}){
 const qc=useQueryClient();const q=useQuery({queryKey:['session'],queryFn:()=>api<Session>('/api/auth/session'),retry:false,staleTime:60_000});
 useEffect(()=>{const cb=()=>qc.setQueryData(['session'],null);window.addEventListener('fab:unauthorized',cb);return()=>window.removeEventListener('fab:unauthorized',cb)},[qc]);
 const login=async(username:string,password:string)=>{resetCsrf();const s=await write<Session>('/api/auth/login',{username,password});await qc.cancelQueries({queryKey:['session']});qc.removeQueries({predicate:q=>q.queryKey[0]!=='session'});qc.setQueryData(['session'],s);resetCsrf()};
 const switchPersona=async(username:string)=>{const s=await write<Session>('/api/auth/demo-personas',{username});await qc.cancelQueries();qc.removeQueries({predicate:q=>q.queryKey[0]!=='session'});qc.setQueryData(['session'],s);resetCsrf()};
 const logout=async()=>{await write('/api/auth/logout',{});resetCsrf();await qc.cancelQueries();qc.removeQueries({predicate:q=>q.queryKey[0]!=='session'});qc.setQueryData(['session'],null)};
 return <Auth.Provider value={{session:q.data,loading:q.isPending,login,switchPersona,logout}}>{children}</Auth.Provider>;
}
export const useAuth=()=>useContext(Auth);
export function Protected({children}:{children:ReactNode}){const {session,loading}=useAuth();const l=useLocation();if(loading)return <div className="screen-message">세션 확인 중…</div>;return session?.authenticated?<>{children}</>:<Navigate to="/login" replace state={{from:l.pathname}}/>}
