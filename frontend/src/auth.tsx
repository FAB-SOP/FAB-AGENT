import {createContext,useContext,useEffect,type ReactNode} from 'react';
import {useQuery,useQueryClient} from '@tanstack/react-query';
import {Navigate,useLocation} from 'react-router-dom';
import {api,resetCsrf,write} from './api/client';
import type {Session} from './api/types';
type AuthValue={
 session:Session|null|undefined;
 loading:boolean;
 login:(username:string,password:string)=>Promise<void>;
 switchPersona:(username:string)=>Promise<void>;
 logout:()=>Promise<void>;
};
const Auth=createContext<AuthValue>(null!);
const isNotSessionQuery=(query:{queryKey:readonly unknown[]})=>query.queryKey[0]!=='session';
export function AuthProvider({children}:{children:ReactNode}){
 const queryClient=useQueryClient();
 const sessionQuery=useQuery({queryKey:['session'],queryFn:()=>api<Session>('/api/auth/session'),retry:false,staleTime:60_000});
 useEffect(()=>{
  const clearSession=()=>queryClient.setQueryData(['session'],null);
  window.addEventListener('fab:unauthorized',clearSession);
  return()=>window.removeEventListener('fab:unauthorized',clearSession);
 },[queryClient]);
 const login=async(username:string,password:string)=>{
  resetCsrf();
  const session=await write<Session>('/api/auth/login',{username,password});
  await queryClient.cancelQueries({queryKey:['session']});
  queryClient.removeQueries({predicate:isNotSessionQuery});
  queryClient.setQueryData(['session'],session);
  resetCsrf();
 };
 const switchPersona=async(username:string)=>{
  const session=await write<Session>('/api/auth/demo-personas',{username});
  await queryClient.cancelQueries();
  queryClient.removeQueries({predicate:isNotSessionQuery});
  queryClient.setQueryData(['session'],session);
  resetCsrf();
 };
 const logout=async()=>{
  await write('/api/auth/logout',{});
  resetCsrf();
  await queryClient.cancelQueries();
  queryClient.removeQueries({predicate:isNotSessionQuery});
  queryClient.setQueryData(['session'],null);
 };
 return <Auth.Provider value={{session:sessionQuery.data,loading:sessionQuery.isPending,login,switchPersona,logout}}>
  {children}
 </Auth.Provider>;
}
export const useAuth=()=>useContext(Auth);
export function Protected({children}:{children:ReactNode}){
 const {session,loading}=useAuth();
 const location=useLocation();
 if(loading)return <div className="screen-message">세션 확인 중…</div>;
 return session?.authenticated?<>{children}</>:<Navigate to="/login" replace state={{from:location.pathname}}/>;
}
