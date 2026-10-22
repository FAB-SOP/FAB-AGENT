package com.fabcontext.api.domain.incident;

public enum IncidentStatus {
	/** 접수 직후, 기본 분석 전 */
	OPEN,
	/** 조사 중 */
	INVESTIGATING,
	/** 확인 요청 회신 대기 */
	WAITING_REPLY,
	/** 관리자 보류 */
	ON_HOLD,
	/** 관리자 종료 (자동 종료 없음) */
	CLOSED
}
