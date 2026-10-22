package com.fabcontext.api.domain.approval;

/**
 * 승인 대상 변경의 종류. 이 이름이 DB 저장값이자 공개 API(프론트 계약) 값이다.
 * 내부 API · Agent 제안(팀 계약)의 이름은 contract 패키지의 ActionTypeNames가 바꾼다(approval은 contract를 모른다).
 */
public enum ApprovalActionType {
	/** 다른 공정 담당자에게 확인 요청 발송 */
	CHECK_REQUEST,
	/** 같은 공정 설비정비팀에 점검 요청 (팩 계약 create_maintenance_request) — 장비 설정은 바꾸지 않는다. 프론트 계약에 새로 더한 값 */
	MAINTENANCE_REQUEST,
	/** 실제 수행한 조치 기록 (AI 대리 기록) */
	RECORD_ACTION,
	/** 보고서 확정 */
	CONFIRM_REPORT
}
