package com.fabcontext.api.domain.checkrequest;

import java.time.Instant;
import com.fabcontext.api.common.Meta;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;
import java.util.Map;

import com.fabcontext.api.domain.incident.Incident;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public final class CheckRequestDtos {

	private CheckRequestDtos() {
	}

	public record CheckRequestSummary(String requestId, String incidentId, String incidentDisplayId,
			String targetProcessCode, String question, CheckRequestStatus status, String requesterUserId,
			String ownerUserId, long version, Instant dueAt, Instant createdAt, Instant repliedAt, Instant decisionAt, String decisionComment, List<String> allowedActions) {

		static CheckRequestSummary from(CheckRequest r, String displayId, String userId) {
			return new CheckRequestSummary(r.getRequestId(), r.getIncidentId(), displayId, r.getTargetProcessCode(),
				r.getQuestion(), r.getStatus(), r.getRequesterUserId(), r.getOwnerUserId(), r.getVersion(),
				r.getDueAt(), r.getCreatedAt(), r.getRepliedAt(), r.getDecisionAt(), r.getDecisionComment(), allowed(r,userId));
		}
	}

	/** 요청받은 담당자가 사건 권한 없이도 볼 수 있는 최소 사건 맥락 */
	public record IncidentContext(String incidentId, String displayId, String title, String processCode,
			String equipmentId, String chamberId, String lotId, List<String> waferIds, Instant detectedAt) {

		static IncidentContext from(Incident i) {
			return i == null ? null
				: new IncidentContext(i.getIncidentId(), i.getDisplayId(), i.getTitle(), i.getProcessCode(),
					i.getEquipmentId(), i.getChamberId(), i.getLotId(), List.copyOf(i.getWaferIds()), i.getDetectedAt());
		}
	}

	public record ReplyView(String replyId, String requestId, String findings, List<String> evidenceIds,
			String authorUserId, boolean confirmedByUser, Instant createdAt, String resumeJobId) {

		static ReplyView from(Reply r) {
			return new ReplyView(r.getReplyId(), r.getRequestId(), r.getFindings(), List.copyOf(r.getEvidenceIds()),
				r.getAuthorUserId(), r.isConfirmedByUser(), r.getCreatedAt(), r.getResumeJobId());
		}
	}

	public record CheckRequestView(String requestId, String incidentId, IncidentContext incident,
			String targetProcessCode, String approvalId, String requestType, String equipmentId, String question,
			Map<String, Object> brief, List<String> causeIds, List<String> evidenceIds,
			String requesterUserId, String ownerUserId, CheckRequestStatus status, long version, String threadId,
			String waitingJobId, Instant dueAt, Instant createdAt, Instant repliedAt, List<ReplyView> replies, Instant decisionAt, String decisionComment, List<String> allowedActions,
			String withdrawnBy, Instant withdrawnAt, String withdrawReason) {
	}

	@Schema(name = "CheckRequestWithdrawal")
	public record WithdrawalInput(@NotBlank @Size(max = 500) String reason, @NotNull Long expectedVersion) {}

	@Schema(name="CheckRequestDecisionInput")
	public record DecisionInput(@Schema(allowableValues={"ACCEPT","REJECT"}, example="ACCEPT") @NotBlank @jakarta.validation.constraints.Pattern(regexp="ACCEPT|REJECT") String decision,
		@Size(max=2000) String comment, @NotNull Long expectedVersion) {}

	static List<String> allowed(CheckRequest r, String userId) {
		if (!userId.equals(r.getOwnerUserId())) return List.of();
		return switch(r.getStatus()) { case OPEN -> List.of("ACCEPT","REJECT"); case ACCEPTED -> List.of("REPLY"); default -> List.of(); };
	}

	public record DecisionEnvelope(CheckRequestView data, Meta meta) {}
	public record ReplyEnvelope(ReplyResult data, Meta meta) {}
	public record ReplyInput(@NotBlank @Size(max = 4000) String findings, List<String> evidenceIds,
			@NotNull Boolean confirmedByUser, @NotNull Long expectedVersion) {
	}

	public record ReplyResult(String replyId, String requestId, String findings, List<String> evidenceIds,
			String authorUserId, Instant createdAt, String resumeJobId, CheckRequestStatus requestStatus,
			long requestVersion) {
	}
}
