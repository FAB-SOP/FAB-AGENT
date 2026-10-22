import {useState} from 'react';
import {useQuery} from '@tanstack/react-query';
import {ArrowUpRight} from 'lucide-react';
import {api} from '../api/client';
import type {Obj} from '../api/types';
import {Empty,ErrorMessage,PageHeading,Panel} from '../components/ui';
export function Knowledge(){
 const [query,setQuery]=useState('');
 const documents=useQuery({queryKey:['knowledge',query],queryFn:()=>api<Obj>('/api/knowledge/documents?q='+encodeURIComponent(query))});
 return <>
  <PageHeading kicker="REFERENCE LIBRARY" title="참고 문서" description="공정별 기술 자료와 과거 사례를 검색합니다."/>
  <div className="filter-row">
   <input aria-label="참고 문서 검색" placeholder="문서명 또는 내용 검색" value={query} onChange={event=>setQuery(event.target.value)}/>
  </div>
  {documents.isError&&<ErrorMessage error={documents.error}/>}
  <div className="document-grid">
   {documents.data?.items?.map((document:Obj)=><Panel key={document.docId+'-'+document.sectionId}>
    <p className="eyebrow">{document.docId} · {document.synthetic?'가상 사례/문서':'공개 자료'}</p>
    <h2>{document.title}</h2>
    <p>{document.text}</p>
    <p className="small muted">{document.isApprovedProcedure?'승인 절차':'기술 참고 자료'} · {document.version}</p>
    {/^https?:\/\//.test(document.resourceUri||'')&&<a
     className="text-button"
     href={document.resourceUri}
     target="_blank"
     rel="noreferrer"
    >실제 원문 열기 <ArrowUpRight size={17}/></a>}
   </Panel>)}
  </div>
  {!documents.isPending&&!documents.data?.items?.length&&!documents.isError&&<Empty>검색 결과가 없습니다.</Empty>}
 </>;
}
