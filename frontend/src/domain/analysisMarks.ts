import {type Obj} from '../api/types';
/** Agent v2 결과 계약(docs/agent-result-contract-backend.md) — 값이 없으면 아무것도 추정하지 않는다 */
const stances:Record<string,{label:string;tone:string}>={
 SUPPORTED:{label:'지지',tone:'supported'},
 REFUTED:{label:'반증',tone:'refuted'},
 UNDETERMINED:{label:'판정 보류',tone:'undetermined'},
};
/** 후보 stance 뱃지 — 사람의 확정이 아니라 AI의 근거 판단. 없거나 모르는 값이면 null */
export const stanceView=(stance?:string|null)=>stance?stances[stance]||null:null;
/** 결론이 바뀐 답만 이유·바뀐 항목·새 근거를 돌려준다 */
export function conclusionChange(result?:Obj|null):{reason:string;fields:string[];newEvidenceIds:string[]}|null{
 const conclusion=result?.analysis?.conclusion;
 if(!conclusion?.changed)return null;
 return {reason:conclusion.reason||'',fields:conclusion.fields||[],newEvidenceIds:conclusion.newEvidenceIds||[]};
}
/** 본문 — 새 계약의 answer(전체 답)가 있으면 그것, 없으면 기존 summary */
export const answerText=(result?:Obj|null)=>result?.answer||result?.summary||'';
/** 반대 근거·다음 확인은 새 이름(conflictingEvidenceIds·nextCheck)과 기존 이름을 함께 읽는다 */
export const counterIds=(cause:Obj)=>cause.conflictingEvidenceIds||cause.counterEvidence||cause.counterEvidenceIds||[];
export const nextCheck=(cause:Obj)=>cause.nextCheck||cause.check||'';
const nextSteps:Record<string,{label:string;hint:string}>={
 CURRENT_CHECK:{label:'현재 공정 확인',hint:'이상이 난 공정에서 먼저 확인합니다.'},
 UPSTREAM_CHECK:{label:'앞 공정 확인',hint:'앞 공정 담당자의 확인이 먼저 필요합니다.'},
 OBSERVE:{label:'관찰 유지',hint:'지금은 조치 없이 추이를 지켜봅니다.'},
 INSUFFICIENT_EVIDENCE:{label:'근거 부족',hint:'판단할 자료가 부족합니다. 자료를 더 모아야 합니다.'},
 NEEDS_DEEP_ANALYSIS:{label:'추가 분석 필요',hint:'질문하거나 결과를 골라 더 분석해야 합니다.'},
};
/** AI가 정한 다음 단계(analysis.nextStep) — 없거나 모르는 값이면 null */
export const nextStepView=(result?:Obj|null)=>nextSteps[result?.analysis?.nextStep]||null;
const fieldLabels:Record<string,string>={topCause:'1순위 원인',nextStep:'다음 확인',targetProcess:'대상 공정',causeIds:'원인 후보'};
export const conclusionFieldLabel=(field:string)=>fieldLabels[field]||field;
