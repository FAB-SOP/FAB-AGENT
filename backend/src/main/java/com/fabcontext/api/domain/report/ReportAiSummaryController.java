package com.fabcontext.api.domain.report;

import java.util.Map;

import com.fabcontext.api.auth.AuthUser;
import com.fabcontext.api.auth.SecurityConfig;
import com.fabcontext.api.common.ApiResponse;
import com.fabcontext.api.common.IdempotencyService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
public class ReportAiSummaryController {

	private final ReportAiSummaryService service;
	private final IdempotencyService idempotency;

	@Tag(name = "reports")
	@Operation(operationId = "retryReportSummary", summary = "실패한 확정본 요약 다시 요청",
		description = "summary.status가 FAILED일 때만 새 요약 작업을 접수하고 PENDING으로 바꾼다(그 외 409 SUMMARY_NOT_RETRYABLE). 확정 권한(사건 운영 권한)이 필요하며 종료된 사건에서도 허용한다.")
	@PostMapping("/api/reports/{reportId}/summary-jobs")
	public ResponseEntity<?> retryReportSummary(@AuthenticationPrincipal AuthUser user, @PathVariable String reportId,
			@RequestHeader(SecurityConfig.CSRF_HEADER) String csrf,
			@RequestHeader(IdempotencyService.HEADER) String idempotencyKey) {
		return idempotency.execute(user.userId(), "retryReportSummary:" + reportId, idempotencyKey, Map.of(),
			() -> ResponseEntity.accepted().body(ApiResponse.of(service.retry(reportId, user))));
	}
}
