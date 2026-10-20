package com.fabcontext.api.internal.job;

import java.util.List;
import java.util.Map;

import com.fabcontext.api.common.ApiResponse;
import com.fabcontext.api.internal.AgentEventService;
import com.fabcontext.api.internal.InternalApi;
import io.swagger.v3.oas.annotations.Hidden;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** Agent Worker — 그래프 제어(모델 도구 목록에는 없다) · 보고서 초안 · Kafka 전까지의 HTTP 작업 소스와 이벤트 수신 */
@Hidden
@RestController
@RequestMapping("/internal")
@RequiredArgsConstructor
class AgentJobController implements InternalApi {

	private final AgentJobService service;

	private final AgentEventService events;

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
