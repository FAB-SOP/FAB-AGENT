package com.fabcontext.api.domain.job;

public enum JobKind {
	/** 사건 접수 직후 기본 분석 */
	BASIC,
	/** 사용자 추가 질문 분석 */
	DEEP,
	/** 승인·회신 이후 같은 threadId로 조사 재개 */
	RESUME,
	/** 기록 기반 보고서 초안 생성 */
	REPORT,
	/** 확정된 보고서의 1~2줄 요약 — 종료된 사건에서도 돈다 */
	SUMMARY;

	/** 사건의 조사 상태(현재 작업 · 최근 작업 상태 · 분석 완료 알림)에 반영하는 작업인지 */
	public boolean affectsInvestigationState() {
		return this != SUMMARY;
	}
}
