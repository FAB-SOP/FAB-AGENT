import {type Obj} from '../api/types';
import {conclusionChange,conclusionFieldLabel,stanceView} from '../domain/analysisMarks';

/** 후보 지지·반증·판정 보류 — 서버가 보낸 stance만 표시 */
export function StanceBadge({stance}:{stance?:string|null}){
 const view=stanceView(stance);
 return view?<span className={'stance-badge '+view.tone} title="AI의 근거 판단 · 확정 아님">{view.label}</span>:null;
}
/** 질문·추가 분석으로 결론이 바뀐 답에만 이유와 새 근거를 보여준다 */
export function ConclusionNotice({result}:{result?:Obj|null}){
 const change=conclusionChange(result);
 if(!change)return null;
 return <div className="conclusion-changed" role="note">
  <strong>질문으로 결론이 바뀜</strong>
  {change.fields.length>0&&<span className="small"> · {change.fields.map(conclusionFieldLabel).join(', ')}</span>}
  {change.reason&&<p>{change.reason}</p>}
  {change.newEvidenceIds.length>0&&<p className="small muted">새 근거 · {change.newEvidenceIds.join(', ')}</p>}
 </div>;
}
/** 결과 종류 — 질문 답 중 원인 분석을 갱신한 것만 표시 */
export function ResultKindTag({result}:{result?:Obj|null}){
 return result?.resultKind==='CAUSE_ANALYSIS'&&result?.kind!=='BASIC'?<span className="result-kind">원인 분석 갱신</span>:null;
}
