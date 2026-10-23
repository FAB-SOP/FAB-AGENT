package com.fabcontext.api.domain.report;

/** 보고서 확정을 막는 남은 일 하나 — 무엇이(kind) · 어느 것(id) · 사람이 읽을 이름(label) */
public record ConfirmationBlocker(Kind kind, String id, String label) {

	public enum Kind {
		/** 진행 · 대기 중인 조사 분석(Agent 승인 대기 · 회신 대기 포함) */
		ANALYSIS_IN_PROGRESS,
		/** 사람의 승인 · 거절을 기다리는 Agent 제안 */
		APPROVAL_PENDING,
		/** 관리자 승인을 기다리는 업무 */
		MANAGER_APPROVAL_PENDING,
		/** 회신이 오지 않은 확인 요청(대기 · 수락됨) */
		REQUEST_UNANSWERED,
		/** 채택했지만 수행하지 않은 업무 */
		WORK_UNPERFORMED
	}
}
