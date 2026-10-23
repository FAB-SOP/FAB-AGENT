package com.fabcontext.api.domain.report;

/** 확정본 요약 상태 — 대기 → 진행 → 완료 / 실패, 실패에서만 다시 대기로 */
public enum ReportAiSummaryStatus {
	PENDING,
	RUNNING,
	READY,
	FAILED
}
