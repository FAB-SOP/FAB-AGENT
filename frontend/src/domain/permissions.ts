import {type Session} from '../api/types';
/** 서버가 내려준 사건 운영 범위. 구버전 응답은 쓰기 불가로 취급한다. */
export const canManageInvestigation=(session:Session|null|undefined,process:string)=>!!session?.investigationProcessScopes?.includes(process);
/** 원인 통계 — 관리자 또는 설비정비팀 엔지니어만. 일반 엔지니어는 안 됨(서버도 403 CAUSE_STATISTICS_ACCESS_DENIED) */
export const canViewCauseStatistics=(session:Session|null|undefined)=>
 !!session&&(session.role==='MANAGER'||session.role==='ENGINEER'&&session.user.team==='설비정비팀');
