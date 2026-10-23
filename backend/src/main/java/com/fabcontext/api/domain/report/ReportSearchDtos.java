package com.fabcontext.api.domain.report;

import java.time.Instant;
import java.util.List;

import com.fabcontext.api.domain.report.ReportDtos.ClassificationView;

/** 보고서 목록 조회(GET /api/reports) 응답 */
public final class ReportSearchDtos {

	private ReportSearchDtos() {
	}

	/** 목록 한 행 — 보고서와 그 사건의 이상 감지 시각을 함께 담는다 */
	public record ReportListItem(String reportId, String incidentId, String incidentDisplayId, String processCode,
			int version, ReportStatus status, String title, long contentRevision, Instant createdAt,
			Instant confirmedAt, Instant detectedAt, ClassificationView classification) {
	}

	/** 페이지 한 장 — 전체 건수는 세지 않고 다음 페이지가 있는지만 알린다 */
	public record ReportSlice(List<ReportListItem> items, int page, int size, boolean hasNext) {
	}
}
