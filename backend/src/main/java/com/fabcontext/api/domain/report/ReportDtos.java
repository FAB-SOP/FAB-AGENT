package com.fabcontext.api.domain.report;

import java.time.Instant;
import java.util.List;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import com.fabcontext.api.contract.ReportSection;

public final class ReportDtos {

	private ReportDtos() {
	}

	public record ReportSummary(String reportId, int version, ReportStatus status, String title,
			long contentRevision, Instant createdAt, Instant confirmedAt, ClassificationView classification,
			AiSummaryView summary) {

		static ReportSummary from(Report r) {
			return new ReportSummary(r.getReportId(), r.getVersion(), r.getStatus(), r.getTitle(),
				r.getContentRevision(), r.getCreatedAt(), r.getConfirmedAt(), ClassificationView.from(r.getClassification()),
				AiSummaryView.from(r.getAiSummary()));
		}
	}

	/** 확정본의 AI 요약 — 초안 · 도입 전 확정본은 응답에서 null */
	@Schema(name = "ReportAiSummary")
	public record AiSummaryView(ReportAiSummaryStatus status, List<String> lines, List<String> evidenceIds,
			@Schema(allowableValues = {"model", "rule"}) String generatedBy, Instant updatedAt, String error) {

		static AiSummaryView from(ReportAiSummary s) {
			if (s == null || s.getStatus() == null) {
				return null;
			}
			return new AiSummaryView(s.getStatus(), s.getLines(), s.getEvidenceIds(), s.getGeneratedBy(), s.getUpdatedAt(),
				s.getError());
		}
	}

	/** 확정할 때 고른 원인 분류 — 없으면(초안 · 도입 전 확정본) 응답에서 null */
	public record ClassificationView(ReportCauseCategory category, List<String> equipmentParameters,
			String classifiedBy, Instant classifiedAt) {

		static ClassificationView from(ReportCauseClassification c) {
			if (c == null || c.getCauseCategory() == null) {
				return null;
			}
			return new ClassificationView(c.getCauseCategory(), c.getCauseEquipmentParameters(), c.getClassifiedBy(),
				c.getClassifiedAt());
		}
	}

	public record ReportPage(String incidentId, List<ReportSummary> items) {
	}

	public record ReportView(String reportId, String incidentId, int version, ReportStatus status, String title,
			long contentRevision, long sourceRevision, List<ReportSection> sections, List<String> evidenceIds,
			Integer baseReportVersion, String generatedByJobId, String snapshotRef, String createdBy,
			Instant createdAt, String updatedBy, Instant updatedAt, String confirmedBy, Instant confirmedAt,
			ClassificationView classification, AiSummaryView summary) {

		public static ReportView from(Report r) {
			return new ReportView(r.getReportId(), r.getIncidentId(), r.getVersion(), r.getStatus(), r.getTitle(),
				r.getContentRevision(), r.getSourceRevision(),
				r.getSections().stream().map(s -> new ReportSection(String.valueOf(s.get("key")), String.valueOf(s.get("title")),
					s.get("body") == null ? "" : String.valueOf(s.get("body")))).toList(),
				List.copyOf(r.getEvidenceIds()), r.getBaseReportVersion(), r.getGeneratedByJobId(), r.getSnapshotRef(),
				r.getCreatedBy(), r.getCreatedAt(), r.getUpdatedBy(), r.getUpdatedAt(), r.getConfirmedBy(),
				r.getConfirmedAt(), ClassificationView.from(r.getClassification()), AiSummaryView.from(r.getAiSummary()));
		}
	}

	public record ReportGeneration(Integer baseReportVersion, @NotNull Long expectedIncidentVersion) {
	}

	public record ReportEdit(@Size(max = 300) String title, @NotNull @jakarta.validation.constraints.NotEmpty
			List<@Valid ReportSection> sections,
			@NotNull Long expectedContentRevision) {
	}

	/** classification은 서버가 검사한다({@link CauseClassificationPolicy}) — 누락이면 422 */
	public record ReportConfirm(@NotNull Long expectedContentRevision, @NotNull Boolean reviewConfirmed,
			@Valid CauseClassificationInput classification) {
	}
}
