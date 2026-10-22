package com.fabcontext.api.domain.work;
import java.time.Instant;
import java.util.List;
import jakarta.validation.constraints.*;
public final class WorkDtos {
	private WorkDtos() {}
	@io.swagger.v3.oas.annotations.media.Schema(name="WorkProposal")
	public record Proposal(@NotNull WorkOperation operationCode, @NotBlank @Size(max=2000) String title,
		@NotBlank @Size(max=2000) String reason, @NotBlank String sourceResultId, String sourceCauseId,
		List<String> evidenceIds, @NotNull Long expectedIncidentVersion) {}
	@io.swagger.v3.oas.annotations.media.Schema(name="WorkAdoption")
	public record Adoption(@NotNull Long expectedVersion, String ownerUserId, String targetProcessCode) {}
	@io.swagger.v3.oas.annotations.media.Schema(name="WorkDecision")
	public record Decision(@NotBlank @Pattern(regexp="APPROVE|REJECT|DECLINE") String decision,
		@Size(max=2000) String comment, @NotNull Long expectedVersion) {}
	@io.swagger.v3.oas.annotations.media.Schema(name="WorkCompletion")
	public record Completion(@NotBlank @Size(max=4000) String description, @NotNull Instant performedAt,
		@NotNull Boolean confirmedByUser, @NotNull Long expectedVersion, @NotNull Long expectedIncidentVersion) {}
	@io.swagger.v3.oas.annotations.media.Schema(name="WorkItemView")
	public record View(String taskId, String incidentId, String processCode, String equipmentId,
		WorkOperation operationCode, String operationLabel, WorkOperation.Route route, String title, String reason,
		String sourceResultId, String sourceCauseId, List<String> evidenceIds, String createdBy, String requestedBy,
		String assigneeUserId, String approverUserId, String targetProcessCode, String requestId, String actionId,
		String replyId, WorkItem.Status status, String decisionComment, long version, Instant createdAt, Instant updatedAt) {
		public static View from(WorkItem w) { return new View(w.getTaskId(),w.getIncidentId(),w.getProcessCode(),w.getEquipmentId(),
			w.getOperationCode(),w.getOperationCode().label,w.getRoute(),w.getTitle(),w.getReason(),w.getSourceResultId(),
			w.getSourceCauseId(),w.getEvidenceIds(),w.getCreatedBy(),w.getRequestedBy(),w.getAssigneeUserId(),w.getApproverUserId(),
			w.getTargetProcessCode(),w.getRequestId(),w.getActionId(),w.getReplyId(),w.getStatus(),w.getDecisionComment(),w.getVersion(),w.getCreatedAt(),w.getUpdatedAt()); }
	}
}
