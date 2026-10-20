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

	public record ApprovalDecision(@NotNull @Pattern(regexp = "APPROVE|REJECT") String decision,
			@Size(max = 2000) String comment, @NotNull Long expectedVersion, String actionHash) {
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
			Ref executionRef, String resumeJobId) {

		public static ApprovalView from(Approval a) {
			return new ApprovalView(a.getApprovalId(), a.getIncidentId(), a.getProcessCode(), a.getActionType(),
				Map.copyOf(a.getPayload()), a.getRationale(), List.copyOf(a.getEvidenceIds()), a.getActionHash(),
				a.getStatus(), a.getVersion(), a.getExpiresAt(), a.getJobId(), a.getSource(), a.getCreatedBy(),
				a.getCreatedAt(), a.getDecidedBy(), a.getDecidedAt(), a.getDecisionComment(), a.executionRef(),
				a.getResumeJobId());
		}
	}

	public record ApprovalDecisionResult(ApprovalView approval, String resumeJobId, Ref executionRef) {
	}
}
