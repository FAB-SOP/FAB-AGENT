package com.fabcontext.api.domain.report;

import java.net.URI;
import java.nio.charset.StandardCharsets;

import com.fabcontext.api.auth.AuthUser;
import com.fabcontext.api.auth.SecurityConfig;
import com.fabcontext.api.common.ApiResponse;
import com.fabcontext.api.common.IdempotencyService;
import com.fabcontext.api.domain.report.ReportDtos.ReportConfirm;
import com.fabcontext.api.domain.report.ReportDtos.ReportEdit;
import com.fabcontext.api.domain.report.ReportDtos.ReportGeneration;
import com.fabcontext.api.domain.report.ReportDtos.ReportPage;
import com.fabcontext.api.domain.report.ReportDtos.ReportView;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
public class ReportController {

	private final ReportService service;
	private final IdempotencyService idempotency;

	@Tag(name = "incidents")
	@Operation(operationId = "listReports", summary = "보고서 버전 목록")
	@GetMapping("/api/incidents/{incidentId}/reports")
	public ApiResponse<ReportPage> listReports(@AuthenticationPrincipal AuthUser user, @PathVariable String incidentId) {
		return ApiResponse.of(service.list(incidentId, user));
	}

	@Tag(name = "incidents")
	@Operation(operationId = "generateReport", summary = "기록 기반 보고 초안 생성 작업")
	@PostMapping("/api/incidents/{incidentId}/report-jobs")
	public ResponseEntity<?> generateReport(@AuthenticationPrincipal AuthUser user, @PathVariable String incidentId,
			@RequestHeader(SecurityConfig.CSRF_HEADER) String csrf,
			@RequestHeader(IdempotencyService.HEADER) String idempotencyKey,
			@Valid @RequestBody ReportGeneration body) {
		return idempotency.execute(user.userId(), "generateReport:" + incidentId, idempotencyKey, body, () -> {
			var accepted = service.generate(incidentId, user, body);
			return ResponseEntity.accepted().location(URI.create(accepted.statusUrl())).body(ApiResponse.of(accepted));
		});
	}

	@Tag(name = "reports")
	@Operation(operationId = "getReport", summary = "특정 보고서 버전 조회")
	@GetMapping("/api/reports/{reportId}")
	public ApiResponse<ReportView> getReport(@AuthenticationPrincipal AuthUser user, @PathVariable String reportId) {
		return ApiResponse.of(service.get(reportId, user));
	}

	@Tag(name = "reports")
	@Operation(operationId = "editReport", summary = "초안 편집")
	@PatchMapping("/api/reports/{reportId}")
	public ApiResponse<ReportView> editReport(@AuthenticationPrincipal AuthUser user, @PathVariable String reportId,
			@RequestHeader(SecurityConfig.CSRF_HEADER) String csrf, @Valid @RequestBody ReportEdit body) {
		return ApiResponse.of(service.edit(reportId, user, body));
	}

	@Tag(name = "reports")
	@Operation(operationId = "confirmReport", summary = "사용자 검토 후 보고서 확정")
	@PostMapping("/api/reports/{reportId}/confirmations")
	public ResponseEntity<?> confirmReport(@AuthenticationPrincipal AuthUser user, @PathVariable String reportId,
			@RequestHeader(SecurityConfig.CSRF_HEADER) String csrf,
			@RequestHeader(IdempotencyService.HEADER) String idempotencyKey, @Valid @RequestBody ReportConfirm body) {
		return idempotency.execute(user.userId(), "confirmReport:" + reportId, idempotencyKey, body,
			() -> ResponseEntity.ok(ApiResponse.of(service.confirm(reportId, user, body))));
	}

	@Tag(name = "reports")
	@Operation(operationId = "exportReport", summary = "보고서 Markdown 다운로드")
	@GetMapping("/api/reports/{reportId}/export")
	public ResponseEntity<String> exportReport(@AuthenticationPrincipal AuthUser user, @PathVariable String reportId,
			@RequestParam(required = false) String format) {
		ReportService.Export export = service.export(reportId, user, format);
		return ResponseEntity.ok()
			.contentType(new MediaType("text", "markdown", StandardCharsets.UTF_8))
			.header(HttpHeaders.CONTENT_DISPOSITION,
				ContentDisposition.attachment().filename(export.filename(), StandardCharsets.UTF_8).build().toString())
			.body(export.markdown());
	}
}
