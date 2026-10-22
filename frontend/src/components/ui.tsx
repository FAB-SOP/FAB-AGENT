import {type ReactNode,useEffect,useRef} from 'react';
import {X,AlertCircle,ArrowUpRight} from 'lucide-react';
import {labels} from '../api/types';
export function Badge({value,children}:{value?:string;children?:ReactNode}){
 return <span className={'badge '+(value||'').toLowerCase()}>{children||labels[value||'']||value}</span>;
}
export function Panel({title,eyebrow,extra,children,className=''}:{
 title?:string;eyebrow?:string;extra?:ReactNode;children:ReactNode;className?:string;
}){
 return <section className={'panel '+className}>
  {title&&<div className="panel-title">
   <div>
    {eyebrow&&<p className="eyebrow">{eyebrow}</p>}
    <h2>{title}</h2>
   </div>
   {extra}
  </div>}
  {children}
 </section>;
}
export function Notice({children,tone='info'}:{children:ReactNode;tone?:string}){
 return <div className={'notice '+tone}>
  <AlertCircle size={20}/>
  <div>{children}</div>
 </div>;
}
export function Empty({children}:{children:ReactNode}){
 return <div className="empty">{children}</div>;
}
export function ErrorMessage({error,retry}:{error:unknown;retry?:()=>void}){
 return <Notice tone="error">
  <p>{error instanceof Error?error.message:'조회에 실패했습니다.'}</p>
  {retry&&<button className="text-button" onClick={retry}>다시 조회 <ArrowUpRight size={16}/></button>}
 </Notice>;
}
export function Modal({title,children,onClose}:{title:string;children:ReactNode;onClose:()=>void}){
 const dialogRef=useRef<HTMLDialogElement>(null);
 useEffect(()=>{
  dialogRef.current?.showModal();
  return()=>dialogRef.current?.close();
 },[]);
 return <dialog
  aria-label={title}
  ref={dialogRef}
  onCancel={event=>{event.preventDefault();onClose()}}
  onClick={event=>{if(event.target===event.currentTarget)onClose()}}
 >
  <div className="modal-head">
   <h2>{title}</h2>
   <button type="button" className="icon-button" aria-label="닫기" onClick={onClose}><X/></button>
  </div>
  {children}
 </dialog>;
}
export function Field({label,children}:{label:string;children:ReactNode}){
 return <label className="field">
  <span>{label}</span>
  {children}
 </label>;
}
export function PageHeading({kicker,title,description,extra}:{
 kicker:string;title:string;description:string;extra?:ReactNode;
}){
 return <div className="page-heading">
  <div>
   <p className="eyebrow">{kicker}</p>
   <h1>{title}</h1>
   <p>{description}</p>
  </div>
  {extra}
 </div>;
}
