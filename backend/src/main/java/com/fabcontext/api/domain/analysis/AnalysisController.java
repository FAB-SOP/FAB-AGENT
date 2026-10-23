package com.fabcontext.api.domain.analysis;

import java.util.List;
import java.util.Map;

import com.fabcontext.api.auth.AuthUser;
import com.fabcontext.api.common.ApiException;
import com.fabcontext.api.common.ApiResponse;
import com.fabcontext.api.domain.analysis.AnalysisDtos.AnalysisResultView;
import com.fabcontext.api.domain.analysis.AnalysisDtos.HistoryItem;
import com.fabcontext.api.domain.incident.Incident;
import com.fabcontext.api.domain.incident.IncidentService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "incidents")
@RestController
@RequiredArgsConstructor
public class AnalysisController {

	private final IncidentService incidentService;
	private final AnalysisResultRepository repository;

	@Operation(operationId = "getResults", summary = "분석 결과 이력", description = "latest=true 기본 조회는 최신 원인 분석을 우선합니다. latest=false는 전체 이력과 가장 최근 답변을 반환합니다. resultId 지정 시 해당 결과를 반환합니다. 판단 정보 analysis와 후보 stance는 AI 판단이며 사람의 원인 확정이 아닙니다.")
	@GetMapping("/api/incidents/{incidentId}/analysis-results")
	@Transactional(readOnly = true)
	public ApiResponse<AnalysisResultView> getResults(@AuthenticationPrincipal AuthUser user,
			@PathVariable String incidentId, @RequestParam(defaultValue = "true") boolean latest, @RequestParam(required = false) String resultId) {
		Incident incident = incidentService.loadForUser(incidentId, user);
		List<AnalysisResult> results = repository.findByIncidentIdOrderByCreatedAtDesc(incidentId);
		if (results.isEmpty()) {
			throw new ApiException(HttpStatus.NOT_FOUND, "ANALYSIS_NOT_READY",
				"아직 분석 결과가 없습니다. 원본 데이터는 계속 조회할 수 있습니다.",
				Map.of("activeJobId", String.valueOf(incident.getActiveJobId()),
					"jobStatus", String.valueOf(incident.getLatestJobStatus())));
		}
		List<HistoryItem> history = latest ? null : results.stream().map(HistoryItem::from).toList();
		AnalysisResult selected = resultId == null ? (latest ? repository.latestCauseAnalysis(incidentId).orElse(results.getFirst()) : results.getFirst()) : results.stream()
			.filter(r -> resultId.equals(r.getResultId())).findFirst()
			.orElseThrow(() -> ApiException.notFound("analysis result", resultId));
		return ApiResponse.of(AnalysisResultView.from(selected, history));
	}
}
