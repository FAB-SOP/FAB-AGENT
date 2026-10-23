package com.fabcontext.api.domain.report;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import com.fabcontext.api.common.ApiException;
import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 확정본의 AI 요약. 확정과 함께 「대기」로 접수되고, 현재 요약 작업(jobId)의 결과만 받아들인다.
 * 완료된 요약은 덮어쓰지 않는다. 확정 전 보고서 · 기능 도입 전 확정본은 이 값이 없다(null).
 */
@Embeddable
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ReportAiSummary {

	static final int MAX_LINES = 2;
	static final int MAX_LINE_LENGTH = 120;

	@Enumerated(EnumType.STRING)
	@Column(name = "summary_status")
	private ReportAiSummaryStatus status;

	@JdbcTypeCode(SqlTypes.JSON)
	@Column(name = "summary_lines")
	private List<String> lines = new ArrayList<>();

	@JdbcTypeCode(SqlTypes.JSON)
	@Column(name = "summary_evidence_ids")
	private List<String> evidenceIds = new ArrayList<>();

	/** model · rule */
	@Column(name = "summary_generated_by")
	private String generatedBy;

	/** 현재 요약 작업 — 늦게 도착한 예전 작업의 결과를 가려내는 기준 */
	@Column(name = "summary_job_id")
	private String jobId;

	@Column(name = "summary_updated_at")
	private Instant updatedAt;

	/** 실패했을 때 사용자에게 보이는 짧은 안내 */
	@Column(name = "summary_error")
	private String error;

	private ReportAiSummary(String jobId, Instant at) {
		this.status = ReportAiSummaryStatus.PENDING;
		this.jobId = jobId;
		this.updatedAt = at;
	}

	static ReportAiSummary pending(String jobId, Instant at) {
		return new ReportAiSummary(jobId, at);
	}

	/** 현재 요약 작업이고 아직 끝나지 않았을 때만 결과 · 진행을 받아들인다 */
	boolean accepts(String resultJobId) {
		return jobId != null && jobId.equals(resultJobId)
				&& (status == ReportAiSummaryStatus.PENDING || status == ReportAiSummaryStatus.RUNNING);
	}

	void markRunning(String resultJobId, Instant at) {
		if (accepts(resultJobId) && status == ReportAiSummaryStatus.PENDING) {
			status = ReportAiSummaryStatus.RUNNING;
			updatedAt = at;
		}
	}

	void complete(List<String> summaryLines, List<String> summaryEvidenceIds, String summaryGeneratedBy, Instant at) {
		lines = new ArrayList<>(validLines(summaryLines));
		evidenceIds = summaryEvidenceIds == null ? new ArrayList<>() : new ArrayList<>(summaryEvidenceIds);
		generatedBy = summaryGeneratedBy;
		error = null;
		status = ReportAiSummaryStatus.READY;
		updatedAt = at;
	}

	void fail(String message, Instant at) {
		status = ReportAiSummaryStatus.FAILED;
		error = message;
		updatedAt = at;
	}

	boolean isRetryable() {
		return status == ReportAiSummaryStatus.FAILED;
	}

	/** 실패한 요약을 새 작업으로 다시 접수한다 */
	void restart(String newJobId, Instant at) {
		status = ReportAiSummaryStatus.PENDING;
		jobId = newJobId;
		lines = new ArrayList<>();
		evidenceIds = new ArrayList<>();
		generatedBy = null;
		error = null;
		updatedAt = at;
	}

	public List<String> getLines() {
		return lines == null ? List.of() : List.copyOf(lines);
	}

	public List<String> getEvidenceIds() {
		return evidenceIds == null ? List.of() : List.copyOf(evidenceIds);
	}

	private static List<String> validLines(List<String> candidate) {
		List<String> trimmed = candidate == null ? List.of()
			: candidate.stream().filter(line -> line != null && !line.isBlank()).map(String::strip).toList();
		if (trimmed.isEmpty() || trimmed.size() > MAX_LINES) {
			throw ApiException.unprocessable("INVALID_SUMMARY", "요약은 1~" + MAX_LINES + "줄이어야 합니다.");
		}
		if (trimmed.stream().anyMatch(line -> line.length() > MAX_LINE_LENGTH)) {
			throw ApiException.unprocessable("INVALID_SUMMARY", "요약 한 줄은 " + MAX_LINE_LENGTH + "자 이내여야 합니다.");
		}
		return trimmed;
	}
}
