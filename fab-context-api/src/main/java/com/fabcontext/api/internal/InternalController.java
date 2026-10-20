package com.fabcontext.api.internal;

import java.util.List;
import java.util.Map;

import com.fabcontext.api.common.ApiResponse;
import com.fabcontext.api.domain.approval.ApprovalActionType;
import io.swagger.v3.oas.annotations.Hidden;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * 내부 API — 데이터 팩 Agent 계약(agent-contract.json)의 데이터 도구 · 승인 쓰기와 Worker용 작업 제어.
 * 부르는 쪽: 업무 MCP(fab-agent fab.business.server)와 Agent Worker(fab.agent.backend · HTTP 작업 소스).
 * 인증: Authorization: Bearer {fab.internal-token} (ROLE_AGENT). 공개 API의 세션 · CSRF와 무관하다.
 */
@Hidden
@RestController
@RequestMapping("/internal")
@RequiredArgsConstructor
class InternalController {

	private final InternalService service;

	private final AgentEventService events;

	// ---------------- 업무 MCP 읽기 도구 — 사건
	@GetMapping("/incidents/{incidentId}")
	ApiResponse<Map<String, Object>> incident(@PathVariable String incidentId) {
		return ApiResponse.of(service.incident(incidentId));
	}

	@GetMapping("/incidents")
	ApiResponse<Map<String, Object>> incidents(@RequestParam(required = false) String processCode,
			@RequestParam(required = false, defaultValue = "ALL") String status) {
		return ApiResponse.of(service.incidents(processCode, status));
	}

	// ---------------- 업무 MCP 읽기 도구 — 데이터 팩 계약(dataMcpTools)
	@GetMapping("/wafers/{waferId}/lineage")
	ApiResponse<Map<String, Object>> lineage(@PathVariable String waferId) {
		return ApiResponse.of(service.lineage(waferId));
	}

	@GetMapping("/runs/{runId}")
	ApiResponse<Map<String, Object>> run(@PathVariable String runId) {
		return ApiResponse.of(service.run(runId));
	}

	@GetMapping("/runs/{runId}/features")
	ApiResponse<Map<String, Object>> features(@PathVariable String runId, @RequestParam(required = false) String sensorIds) {
		return ApiResponse.of(service.features(runId, InternalService.csv(sensorIds)));
	}

	@GetMapping("/runs/{runId}/traces/{sensorId}")
	ApiResponse<Map<String, Object>> trace(@PathVariable String runId, @PathVariable String sensorId,
			@RequestParam(required = false) String step, @RequestParam(required = false) Integer maxPoints) {
		return ApiResponse.of(service.trace(runId, sensorId, step, maxPoints));
	}

	@GetMapping("/limits")
	ApiResponse<Map<String, Object>> limits(@RequestParam(required = false) String processCode) {
		return ApiResponse.of(service.limits(processCode));
	}

	@GetMapping("/changes")
	ApiResponse<Map<String, Object>> changes(@RequestParam(required = false) String equipmentId) {
		return ApiResponse.of(service.changes(equipmentId));
	}

	// ---------------- 업무 MCP 읽기 도구 — 협업 · 기록
	@GetMapping("/processes/{processCode}/owners")
	ApiResponse<Map<String, Object>> owners(@PathVariable String processCode) {
		return ApiResponse.of(service.owners(processCode));
	}

	@GetMapping("/incidents/{incidentId}/check-requests")
	ApiResponse<Map<String, Object>> checkRequests(@PathVariable String incidentId) {
		return ApiResponse.of(service.checkRequests(incidentId));
	}

	@GetMapping("/incidents/{incidentId}/actions")
	ApiResponse<Map<String, Object>> actions(@PathVariable String incidentId) {
		return ApiResponse.of(service.actions(incidentId));
	}

	@GetMapping("/incidents/{incidentId}/report-context")
	ApiResponse<Map<String, Object>> reportContext(@PathVariable String incidentId) {
		return ApiResponse.of(service.reportContext(incidentId));
	}

	// ---------------- 승인된 변경 실행 — approvalId + idempotencyKey만 받는다
	@PostMapping("/approved-actions/check-request")
	ApiResponse<Map<String, Object>> executeCheckRequest(@RequestBody Map<String, String> body) {
		return ApiResponse.of(service.executeApproved(ApprovalActionType.CHECK_REQUEST, body.get("approvalId"),
			body.get("idempotencyKey")));
	}

	@PostMapping("/approved-actions/maintenance-request")
	ApiResponse<Map<String, Object>> executeMaintenanceRequest(@RequestBody Map<String, String> body) {
		return ApiResponse.of(service.executeApproved(ApprovalActionType.MAINTENANCE_REQUEST, body.get("approvalId"),
			body.get("idempotencyKey")));
	}

	@PostMapping("/approved-actions/action-record")
	ApiResponse<Map<String, Object>> executeActionRecord(@RequestBody Map<String, String> body) {
		return ApiResponse.of(service.executeApproved(ApprovalActionType.RECORD_ACTION, body.get("approvalId"),
			body.get("idempotencyKey")));
	}

	@PostMapping("/approved-actions/report-confirmation")
	ApiResponse<Map<String, Object>> executeReportConfirmation(@RequestBody Map<String, String> body) {
		return ApiResponse.of(service.executeApproved(ApprovalActionType.CONFIRM_REPORT, body.get("approvalId"),
			body.get("idempotencyKey")));
	}

	// ---------------- Agent Worker — 그래프 제어 (모델 도구 목록에는 없다)
	@GetMapping("/jobs/{jobId}/context")
	ApiResponse<Map<String, Object>> jobContext(@PathVariable String jobId) {
		return ApiResponse.of(service.jobContext(jobId));
	}

	@PostMapping("/jobs/{jobId}/claims")
	ApiResponse<Map<String, Object>> claim(@PathVariable String jobId, @RequestBody Map<String, Object> body) {
		return ApiResponse.of(service.claim(jobId, ((Number) body.get("attempt")).intValue(),
			String.valueOf(body.getOrDefault("workerId", "worker")),
			((Number) body.getOrDefault("leaseSeconds", 300)).intValue()));
	}

	@PostMapping("/jobs/{jobId}/proposals")
	@ResponseStatus(HttpStatus.CREATED)
	ApiResponse<Map<String, Object>> propose(@PathVariable String jobId, @RequestBody Map<String, Object> body) {
		return ApiResponse.of(service.propose(jobId, body));
	}

	// ---------------- 보조 — 재개 값 조회 · 보고서 초안 · Kafka 전까지의 HTTP 작업 소스와 이벤트 수신
	@GetMapping("/approvals/{approvalId}")
	ApiResponse<Map<String, Object>> approval(@PathVariable String approvalId) {
		return ApiResponse.of(service.approval(approvalId));
	}

	@GetMapping("/check-requests/{requestId}")
	ApiResponse<Map<String, Object>> checkRequest(@PathVariable String requestId) {
		return ApiResponse.of(service.checkRequest(requestId));
	}

	@PostMapping("/incidents/{incidentId}/report-drafts")
	@ResponseStatus(HttpStatus.CREATED)
	ApiResponse<Map<String, Object>> reportDraft(@PathVariable String incidentId, @RequestBody Map<String, Object> body) {
		return ApiResponse.of(service.reportDraft(incidentId, body));
	}

	@GetMapping("/jobs")
	ApiResponse<List<Map<String, Object>>> queued() {
		return ApiResponse.of(service.queuedJobs());
	}

	@PostMapping("/jobs/{jobId}/events")
	ApiResponse<Map<String, Object>> event(@PathVariable String jobId, @RequestBody Map<String, Object> body) {
		return ApiResponse.of(events.handle(jobId, body));
	}

	@GetMapping("/healthz")
	ApiResponse<Map<String, Object>> health() {
		return ApiResponse.of(Map.of("status", "ok"));
	}
}
