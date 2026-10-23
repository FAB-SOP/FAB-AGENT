import {ApiError} from '../api/client';

/** 보고서 확정을 막는 남은 일 — 서버 409 CONFIRMATION_BLOCKED의 details.blockers 한 칸 */
export type ConfirmationBlockerKind=
 |'ANALYSIS_IN_PROGRESS'|'APPROVAL_PENDING'|'MANAGER_APPROVAL_PENDING'|'REQUEST_UNANSWERED'|'WORK_UNPERFORMED';
export type ConfirmationBlocker={kind:ConfirmationBlockerKind;id:string;label:string};

export type SummaryStatus='PENDING'|'RUNNING'|'READY'|'FAILED';
export type ReportAiSummary={
 status:SummaryStatus;
 lines:string[];
 evidenceIds:string[];
 generatedBy:'model'|'rule'|null;
 updatedAt:string|null;
 error:string|null;
};

const blockerGuides:Record<ConfirmationBlockerKind,{title:string;howTo:string;target:'incident'|'approvals'|'check-requests'|'work-items'}>={
 ANALYSIS_IN_PROGRESS:{title:'진행 중인 조사 분석',howTo:'분석이 끝나거나 대기 중인 승인 · 회신을 처리해 주세요.',target:'incident'},
 APPROVAL_PENDING:{title:'승인 대기 중인 Agent 제안',howTo:'승인함에서 승인하거나 거절해 주세요.',target:'approvals'},
 MANAGER_APPROVAL_PENDING:{title:'관리자 승인 대기 업무',howTo:'관리자가 업무를 승인하거나 반려해야 합니다.',target:'work-items'},
 REQUEST_UNANSWERED:{title:'회신이 오지 않은 확인 요청',howTo:'회신을 기다리거나, 요청자가 사유를 적어 철회해 주세요.',target:'check-requests'},
 WORK_UNPERFORMED:{title:'채택했지만 수행하지 않은 업무',howTo:'수행 결과를 기록하거나, 사유를 적어 미수행으로 닫아 주세요.',target:'work-items'},
};

/** 확정 실패 오류에서 남은 일 목록을 꺼낸다 — 다른 오류면 빈 목록 */
export function blockersFrom(error:unknown):ConfirmationBlocker[]{
 if(!(error instanceof ApiError)||error.code!=='CONFIRMATION_BLOCKED')return [];
 const blockers=(error.details as {blockers?:unknown}|undefined)?.blockers;
 return Array.isArray(blockers)?blockers.filter(isBlocker):[];
}

/** 남은 일 하나를 화면에 보일 제목 · 정리 방법 · 이동 경로로 바꾼다 */
export function describeBlocker(blocker:ConfirmationBlocker,incidentId:string){
 const guide=blockerGuides[blocker.kind];
 const path=guide.target==='incident'?`/incidents/${incidentId}`:`/${guide.target}`;
 return {title:guide.title,howTo:guide.howTo,path,label:blocker.label};
}

export const summaryStatusLabels:Record<SummaryStatus,string>={
 PENDING:'요약 대기',RUNNING:'요약 중',READY:'요약 완료',FAILED:'요약 실패',
};

export const summaryGeneratorLabels:Record<string,string>={model:'AI 모델',rule:'규칙 기반'};

/** 대기 · 진행 중이면 화면이 짧은 주기로 다시 읽는다 */
export function isSummaryInProgress(summary:ReportAiSummary|null|undefined){
 return summary?.status==='PENDING'||summary?.status==='RUNNING';
}

/** 실패한 요약만, 확정 권한이 있는 사람이 다시 요청할 수 있다 */
export function canRetrySummary(summary:ReportAiSummary|null|undefined,canManage:boolean){
 return canManage&&summary?.status==='FAILED';
}

function isBlocker(value:unknown):value is ConfirmationBlocker{
 const candidate=value as Partial<ConfirmationBlocker>|null;
 return !!candidate&&typeof candidate.id==='string'&&typeof candidate.kind==='string'&&candidate.kind in blockerGuides;
}
