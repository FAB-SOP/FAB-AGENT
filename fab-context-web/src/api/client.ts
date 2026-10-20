export const API_MODE:string='real';
export class ApiError extends Error {constructor(public status:number,public code:string,message:string,public details?:unknown){super(message)}}
let csrf:{token:string;headerName:string}|null=null;
export async function api<T=any>(path:string,init:RequestInit={}):Promise<T>{
 const headers=new Headers(init.headers);const write=init.method&&init.method!=='GET';
 if(write){if(!csrf)csrf=await api('/api/auth/csrf');headers.set(csrf!.headerName,csrf!.token);headers.set('Content-Type','application/json');}
 const response=await fetch(path,{...init,credentials:'include',headers});
 if(response.status===204)return undefined as T;
 const body=await response.json().catch(()=>({}));
 if(!response.ok){if(response.status===401)window.dispatchEvent(new Event('fab:unauthorized'));if(body.error?.code==='CSRF_INVALID')csrf=null;throw new ApiError(response.status,body.error?.code||'NETWORK_RESPONSE',body.error?.message||'요청을 처리하지 못했습니다.',body.error?.details)}
 return body.data as T;
}
export const write=<T=any>(path:string,body:unknown,method='POST',key=crypto.randomUUID())=>api<T>(path,{method,headers:{'Idempotency-Key':key},body:JSON.stringify(body)});
export function resetCsrf(){csrf=null}
export async function downloadReport(id:string){const r=await fetch(`/api/reports/${encodeURIComponent(id)}/export?format=markdown`,{credentials:'include'});if(!r.ok)throw new Error('보고서를 내려받지 못했습니다.');const blob=await r.blob();const u=URL.createObjectURL(blob);const a=document.createElement('a');a.href=u;a.download=`FAB-${id}.md`;a.click();URL.revokeObjectURL(u)}
