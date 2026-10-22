/** Wire DTOs based on attached Spring controllers. Map-valued fields stay extensible. */
export type Obj = Record<string, any>;
export interface Session {
 user:{userId:string;username:string;displayName:string;role:'ENGINEER'|'MANAGER';processScopes:string[];team:string};
 role:string;
 processScopes:string[];
 investigationProcessScopes?:string[];
 authenticated:boolean;
 expiresAt:string;
}
export interface Incident {
 incidentId:string;
 displayId:string;
 title:string;
 summary?:string;
 processCode:string;
 equipmentId:string;
 chamberId:string;
 lotId:string;
 waferIds:string[];
 runIds?:string[];
 sensorIds?:string[];
 metrics?:Obj;
 status:string;
 version:number;
 detectedAt:string;
 updatedAt:string;
 activeJobId:string|null;
 activeJob?:Obj|null;
 jobStatus?:string;
 severity:string;
 analysisPriority?:AnalysisPriority|null;
 timeline?:Obj[];
}
/** 조사 우선순위 — 서버(백엔드·AI)가 판정한 값. severity(입력 알림 심각도)와 별개다. 필드명은 협의안. */
export interface AnalysisPriority {
 status:'NOT_EVALUATED'|'PENDING'|'COMPLETED'|'FAILED';
 level:'HIGH'|'MEDIUM'|'LOW'|null;
 reason:string|null;
}
export interface SensorSample {
 sensorId:string;
 sensorName:string;
 runId:string;
 waferId:string;
 equipmentId:string;
 recipeStep:string;
 measuredAt:string;
 value:number;
 unit:string;
 quality:string;
}
export interface RawData {samples:SensorSample[];totalCount:number;truncated:boolean;baseline:Obj|null;dataVersion:string}
export interface Page<T> {items:T[];nextCursor:string|null}
export const labels:Record<string,string>={
 OPEN:'접수',
 INVESTIGATING:'확인·조치 진행',
 WAITING_REPLY:'담당자 회신 대기',
 ON_HOLD:'보류',
 CLOSED:'종료',
 PENDING:'검토 대기',
 APPROVED:'승인',
 REJECTED:'거절',
 EXPIRED:'만료',
 QUEUED:'분석 대기',
 RUNNING:'분석 중',
 WAITING_APPROVAL:'제안 검토 대기',
 SUCCEEDED:'분석 완료',
 FAILED:'분석 실패',
 REPLIED:'회신 완료',
 DRAFT:'초안',
 CONFIRMED:'확정',
 NOT_VERIFIED:'효과 미확인',
 IMPROVED:'개선 확인',
 UNCHANGED:'변화 없음',
 NO_CHANGE:'변화 없음',
 WORSENED:'악화',
 INCONCLUSIVE:'판단 보류',
 MAINTENANCE_REQUEST:'설비 정비 요청',
 CHECK_REQUEST:'공정 확인 요청',
 RECORD_ACTION:'조치 기록',
 CONFIRM_REPORT:'보고서 확정',
};
export const sensorNames:Record<string,string>={
 chamber_pressure:'챔버 압력',
 heater_temperature:'히터 온도',
 gas_SiH4_feed:'SiH₄ 공급 유량',
 gas_N2O:'N₂O 유량',
 rf_forward:'RF 순방향 전력',
 rf_reflected:'RF 반사 전력',
 source_rf_forward:'소스 RF 순방향',
 source_rf_reflected:'소스 RF 반사',
 bias_rf_forward:'바이어스 RF 순방향',
 bias_rf_reflected:'바이어스 RF 반사',
 gas_CF4:'CF₄ 유량',
 gas_CHF3:'CHF₃ 유량',
 esc_temperature:'척 온도',
 backside_he_pressure:'백사이드 He 압력',
 head_pressure:'헤드 압력',
 platen_speed:'플래튼 회전속도',
 head_speed:'헤드 회전속도',
 slurry_flow:'슬러리 유량',
 platen_motor_current:'플래튼 모터 전류',
};
export const fmt=(value:unknown,digits=1)=>
 typeof value==='number'?value.toLocaleString('ko-KR',{maximumFractionDigits:digits,minimumFractionDigits:digits}):'—';
const seoulDateTime=new Intl.DateTimeFormat('ko-KR',{
 month:'2-digit',
 day:'2-digit',
 hour:'2-digit',
 minute:'2-digit',
 timeZone:'Asia/Seoul',
 hour12:false,
});
export const when=(value?:string)=>value?seoulDateTime.format(new Date(value)):'—';
/** 보고서 원인 분류 — 4M과 보류(사건 상태 ON_HOLD와 별개) */
export type CauseCategory='MATERIAL'|'MAN'|'METHOD'|'MACHINE'|'HOLD';
export interface ReportClassification {
 category:CauseCategory;
 equipmentParameters:string[];
 classifiedBy:string;
 classifiedAt:string;
}
