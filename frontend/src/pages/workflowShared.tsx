import {useState} from 'react';
import {useMutation,useQueryClient} from '@tanstack/react-query';
import {ErrorMessage} from '../components/ui';
/** 화면들이 같이 쓰는 저장 명령 — 성공 · 실패 메시지와 세션 외 캐시 갱신 */
export const isNotSessionQuery=(query:{queryKey:readonly unknown[]})=>query.queryKey[0]!=='session';
export function useCommand(){
 const queryClient=useQueryClient();
 const [message,setMessage]=useState('');
 const mutation=useMutation({
  mutationFn:(operation:()=>Promise<unknown>)=>operation(),
  onSuccess:()=>{
   setMessage('저장했습니다. 최신 상태를 불러왔습니다.');
   queryClient.invalidateQueries({predicate:isNotSessionQuery});
  },
  onError:(error:Error)=>{
   setMessage(error.message);
   queryClient.invalidateQueries({predicate:isNotSessionQuery});
  },
 });
 const exec=async(operation:()=>Promise<unknown>,onSaved?:()=>void,successMessage?:string)=>{
  setMessage('');
  try{
   await mutation.mutateAsync(operation);
   if(successMessage)setMessage(successMessage);
   onSaved?.();
   return true;
  }catch{
   return false;
  }
 };
 return {...mutation,message,exec};
}
export function ErrorMessageIfFailed({command}:{command:{isError:boolean;error:unknown}}){
 return command.isError?<ErrorMessage error={command.error}/>:null;
}
