import {useState} from 'react';
import {Navigate,useNavigate} from 'react-router-dom';
import {useForm,type FieldErrors,type UseFormReturn} from 'react-hook-form';
import {zodResolver} from '@hookform/resolvers/zod';
import {z} from 'zod';
import {ArrowRight,Layers3,ShieldCheck} from 'lucide-react';
import {useAuth} from '../auth';
import {API_MODE} from '../api/client';
const schema=z.object({
 username:z.string().min(1,'아이디를 입력해 주세요.'),
 password:z.string().min(1,'비밀번호를 입력해 주세요.'),
});
const demoAccounts=[['cmp.kim','CMP 담당자'],['etch.kim','Etch 담당자'],['cvd.lee','CVD 담당자'],['manager.park','운영 관리자']];
function LoginStory(){
 return <div className="login-story">
  <div className="brand">
   <span className="brand-mark"><Layers3 size={25}/></span>
   <span>FAB CONTEXT</span>
  </div>
  <div>
   <p className="eyebrow">CONNECT THE EVIDENCE</p>
   <h1>이상 징후에서<br/>다음 판단까지.</h1>
   <p className="login-lead">설비 데이터, 공정 맥락, 담당자의 확인을<br/>하나의 조사 흐름으로 연결합니다.</p>
   <div className="login-steps">
    <div><b>01</b><span>관측과 센서 확인</span></div>
    <div><b>02</b><span>근거를 따라 원인 조사</span></div>
    <div><b>03</b><span>확인·조치·보고 기록</span></div>
   </div>
  </div>
  <p className="small">CVD · Etch · CMP / 공정 이상 조사 워크스페이스</p>
 </div>;
}
function DemoAccounts({selected,onPick}:{selected:string;onPick:(username:string)=>void}){
 return <div className="demo-accounts">
  <p className="small muted">시연 계정 선택 · 공통 비밀번호 fab1234!</p>
  <div>
   {demoAccounts.map(([username,label])=><button
    key={username}
    type="button"
    className={'account-option '+(selected===username?'selected':'')}
    onClick={()=>onPick(username)}
   >{label}</button>)}
  </div>
 </div>;
}
type LoginValues=z.infer<typeof schema>;
function CredentialFields({form,errors}:{form:UseFormReturn<LoginValues>;errors:FieldErrors<LoginValues>}){
 return <>
  <label className="field">
   <span>아이디</span>
   <input autoComplete="username" {...form.register('username')} placeholder="담당자 아이디"/>
   {errors.username&&<span className="field-error">{errors.username.message}</span>}
  </label>
  <label className="field">
   <span>비밀번호</span>
   <input type="password" autoComplete="current-password" {...form.register('password')}/>
   {errors.password&&<span className="field-error">{errors.password.message}</span>}
  </label>
 </>;
}
export default function Login(){
 const {session,login}=useAuth();
 const navigate=useNavigate();
 const [error,setError]=useState('');
 const form=useForm<LoginValues>({
  resolver:zodResolver(schema),
  defaultValues:{username:API_MODE==='mock'?'cmp.kim':'',password:API_MODE==='mock'?'fab1234!':''},
 });
 if(session?.authenticated)return <Navigate to="/dashboard" replace/>;
 const submit=form.handleSubmit(async values=>{
  try{
   setError('');
   await login(values.username,values.password);
   navigate('/dashboard',{replace:true});
  }catch(loginError){
   setError(loginError instanceof Error?loginError.message:'로그인에 실패했습니다.');
  }
 });
 const errors=form.formState.errors;
 return <div className="login-page">
  <LoginStory/>
  <div className="login-form-wrap">
   <div className="login-form">
    <span className="eyebrow">WELCOME TO YOUR WORKSPACE</span>
    <h2>공정 워크스페이스 로그인</h2>
    <p className="muted">로그인한 계정의 담당 공정만 표시됩니다.</p>
    <form onSubmit={submit}>
     <CredentialFields form={form} errors={errors}/>
     {error&&<p className="field-error" role="alert">{error}</p>}
     <button className="button full" disabled={form.formState.isSubmitting}>워크스페이스 열기 <ArrowRight size={19}/></button>
    </form>
    {API_MODE==='mock'&&<DemoAccounts
     selected={form.watch('username')}
     onPick={username=>{form.setValue('username',username);form.setValue('password','fab1234!')}}
    />}
    <p className="login-security small"><ShieldCheck size={18}/>{API_MODE==='mock'?'시연 모드':'담당 공정 계정으로 로그인'}</p>
   </div>
  </div>
 </div>;
}
