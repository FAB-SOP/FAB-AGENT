package com.fabcontext.api.domain.approval;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import com.fabcontext.api.common.Ref;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public final class ApprovalDtos {

	private ApprovalDtos() {
	}

	/**
	 * 수동 제안. payload 형식:
	 * CREATE_CHECK_REQUEST {ownerUserId, question, targetProcessCode?, dueAt?} ·
	 * RECORD_ACTION {actionType, description, performedAt} ·
	 * CONFIRM_REPORT {reportId, expectedContentRevision}
	 */
	public record Proposal(@NotNull ApprovalActionType actionType, @NotNull Map<String, Object> payload,
			@NotBlank @Size(max = 2000) String rationale, List<String> evidenceIds, String jobId,
			@NotNull Long expectedIncidentVersion) {
	}

	/**
	 * classification — 보고서 확정(CONFIRM_REPORT)을 승인할 때 승인자가 고른 원인 분류. 그 종류의 승인에서만 필수이고
	 * 검사는 실행기({@link ApprovedActionHandler#normalizeDecisionInput})가 한다. 거절 · 다른 종류는 쓰지 않는다.
	 */
	public record ApprovalDecision(@NotNull @Pattern(regexp = "APPROVE|REJECT") String decision,
			@Size(max = 2000) String comment, @NotNull Long expectedVersion, String actionHash,
			Map<String, Object> classification) {

		/** 승인자 입력이 없는 결정 — 분류 기능 전과 같은 모양(업무 채택의 요청 발송 승인 등) */
		public ApprovalDecision(String decision, String comment, Long expectedVersion, String actionHash) {
			this(decision, comment, expectedVersion, actionHash, null);
		}
	}

	public record ApprovalSummary(String approvalId, String incidentId, String processCode,
			ApprovalActionType actionType, ApprovalStatus status, String source, long version, Instant expiresAt,
			String createdBy, Instant createdAt) {

		public static ApprovalSummary from(Approval a) {
			return new ApprovalSummary(a.getApprovalId(), a.getIncidentId(), a.getProcessCode(), a.getActionType(),
				a.getStatus(), a.getSource(), a.getVersion(), a.getExpiresAt(), a.getCreatedBy(), a.getCreatedAt());
		}
	}

	public record ApprovalView(String approvalId, String incidentId, String processCode,
			ApprovalActionType actionType, Map<String, Object> payload, String rationale, List<String> evidenceIds,
			String actionHash, ApprovalStatus status, long version, Instant expiresAt, String jobId, String source,
			String createdBy, Instant createdAt, String decidedBy, Instant decidedAt, String decisionComment,
			Ref executionRef, String resumeJobId, Map<String, Object> decisionInput) {

		public static ApprovalView from(Approval a) {
			return new ApprovalView(a.getApprovalId(), a.getIncidentId(), a.getProcessCode(), a.getActionType(),
				Map.copyOf(a.getPayload()), a.getRationale(), List.copyOf(a.getEvidenceIds()), a.getActionHash(),
				a.getStatus(), a.getVersion(), a.getExpiresAt(), a.getJobId(), a.getSource(), a.getCreatedBy(),
				a.getCreatedAt(), a.getDecidedBy(), a.getDecidedAt(), a.getDecisionComment(), a.executionRef(),
				a.getResumeJobId(), a.getDecisionInput() == null ? null : Map.copyOf(a.getDecisionInput()));
		}
	}

	public record ApprovalDecisionResult(ApprovalView approval, String resumeJobId, Ref executionRef) {
	}
}
