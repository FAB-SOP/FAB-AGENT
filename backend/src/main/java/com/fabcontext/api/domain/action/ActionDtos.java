package com.fabcontext.api.domain.action;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Pattern;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public final class ActionDtos {

	private ActionDtos() {
	}

	public record VerificationView(String verificationId, VerificationStatus outcome, String rationale,
			List<String> evidenceIds, String verifiedBy, Instant verifiedAt) {

		static VerificationView from(ActionVerification v) {
			return new VerificationView(v.getVerificationId(), v.getOutcome(), v.getRationale(),
				List.copyOf(v.getEvidenceIds()), v.getVerifiedBy(), v.getVerifiedAt());
		}
	}

	public record ActionView(String actionId, String incidentId, String actionType, String description,
			Instant performedAt, String performedBy, List<String> evidenceIds, boolean confirmedByUser, String source,
			String approvalId, VerificationStatus verificationStatus, long version, Instant createdAt,
			List<VerificationView> verifications, Map<String, Object> relatedSource) {

		public static ActionView from(IncidentAction a) {
			return new ActionView(a.getActionId(), a.getIncidentId(), a.getActionType(), a.getDescription(),
				a.getPerformedAt(), a.getPerformedBy(), List.copyOf(a.getEvidenceIds()), a.isConfirmedByUser(),
				a.getSource(), a.getApprovalId(), a.getVerificationStatus(), a.getVersion(), a.getCreatedAt(),
				a.getVerifications().stream().map(VerificationView::from).toList(), a.getRelatedSource());
		}
	}

	public record ActionPage(String incidentId, List<ActionView> items) {
	}

	public record SourceRef(@NotBlank @Pattern(regexp = "CAUSE|NEXT_ACTION|PROPOSAL|WORK_ITEM") String type,
			@NotBlank @Size(max = 200) String refId, @Size(max = 200) String itemId) {
	}

	public record ActionInput(@NotBlank @Size(max = 60) String actionType,
			@NotBlank @Size(max = 4000) String description, @NotNull Instant performedAt, List<String> evidenceIds,
			@NotNull Boolean confirmedByUser, @NotNull Long expectedIncidentVersion, @Valid SourceRef relatedSource) {
		public ActionInput(String actionType, String description, Instant performedAt, List<String> evidenceIds,
				Boolean confirmedByUser, Long expectedIncidentVersion) {
			this(actionType, description, performedAt, evidenceIds, confirmedByUser, expectedIncidentVersion, null);
		}
	}

	public record VerificationInput(@NotNull VerificationStatus outcome, @NotBlank @Size(max = 2000) String rationale,
			List<String> evidenceIds, @NotNull Boolean confirmedByUser, @NotNull Long expectedVersion) {
	}
}
