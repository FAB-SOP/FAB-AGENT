package com.fabcontext.api.internal.query;

import java.util.Map;

import com.fabcontext.api.common.ApiResponse;
import com.fabcontext.api.domain.report.ReportAiSummaryService;
import com.fabcontext.api.internal.InternalApi;
import io.swagger.v3.oas.annotations.Hidden;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 요약(SUMMARY) 작업이 읽는 확정본 — 확정 때 고정한 칸 이름별 글 · 분류 · 근거. 확정 전 보고서는 404 */
@Hidden
@RestController
@RequestMapping("/internal")
@RequiredArgsConstructor
class InternalReportSnapshotController implements InternalApi {

	private final ReportAiSummaryService summaries;

	@GetMapping("/reports/{reportId}/snapshot")
	ApiResponse<Map<String, Object>> snapshot(@PathVariable String reportId) {
		return ApiResponse.of(summaries.confirmedSnapshot(reportId));
	}
}
