package com.fabcontext.api.domain.investigation;

import java.net.URI;
import java.util.LinkedHashMap;
import java.util.Map;

import com.fabcontext.api.auth.AuthUser;
import com.fabcontext.api.auth.SecurityConfig;
import com.fabcontext.api.common.ApiResponse;
import com.fabcontext.api.common.IdempotencyService;
import com.fabcontext.api.domain.incident.Incident;
import com.fabcontext.api.domain.incident.ActiveJobLookup;
import com.fabcontext.api.domain.incident.IncidentService;
import com.fabcontext.api.domain.job.JobDtos.BasicAnalysisRequest;
import com.fabcontext.api.domain.job.JobDtos.Query;
import com.fabcontext.api.common.stream.IncidentStreamHub;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@Tag(name = "incidents")
@RestController
@RequestMapping("/api/incidents/{incidentId}")
@RequiredArgsConstructor
public class InvestigationController {

	private final InvestigationService service;
	private final IncidentService incidentService;
	private final ActiveJobLookup activeJobs;
	private final IncidentStreamHub streamHub;
	private final IdempotencyService idempotency;
	private final TransactionTemplate tx;

	@Operation(operationId = "askQuestion", summary = "추가 조사 작업 접수")
	@PostMapping("/questions")
	public ResponseEntity<?> askQuestion(@AuthenticationPrincipal AuthUser user, @PathVariable String incidentId,
			@RequestHeader(SecurityConfig.CSRF_HEADER) String csrf,
			@RequestHeader(IdempotencyService.HEADER) String idempotencyKey, @Valid @RequestBody Query body) {
		return idempotency.execute(user.userId(), "askQuestion:" + incidentId, idempotencyKey, body, () -> {
			var accepted = service.ask(incidentId, user, body);
			return ResponseEntity.accepted().location(URI.create(accepted.statusUrl())).body(ApiResponse.of(accepted));
		});
	}

	@Operation(operationId = "rerunBasicAnalysis", summary = "기본 분석 다시 실행")
	@PostMapping("/basic-analysis-jobs")
	public ResponseEntity<?> rerunBasicAnalysis(@AuthenticationPrincipal AuthUser user, @PathVariable String incidentId,
			@RequestHeader(SecurityConfig.CSRF_HEADER) String csrf,
			@RequestHeader(IdempotencyService.HEADER) String idempotencyKey,
			@Valid @RequestBody BasicAnalysisRequest body) {
		return idempotency.execute(user.userId(), "rerunBasicAnalysis:" + incidentId, idempotencyKey, body, () -> {
			var accepted = service.rerunBasic(incidentId, user, body);
			return ResponseEntity.accepted().location(URI.create(accepted.statusUrl())).body(ApiResponse.of(accepted));
		});
	}

	/**
	 * 사건별 SSE. 이벤트: snapshot · job.status · analysis.result · approval.created · approval.decided ·
	 * check-request.created · check-request.replied · action.recorded · action.verified · report.ready ·
	 * report.confirmed · incident.updated. 연결이 끊기면 getJob으로 복구한다.
	 */
	@Operation(operationId = "incidentStream", summary = "SSE 진행 구독")
	@GetMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
	public SseEmitter incidentStream(@AuthenticationPrincipal AuthUser user, @PathVariable String incidentId) {
		Map<String, Object> snapshot = tx.execute(status -> {
			Incident incident = incidentService.loadForUser(incidentId, user);
			Map<String, Object> s = new LinkedHashMap<>();
			s.put("status", incident.getStatus().name());
			s.put("version", incident.getVersion());
			s.put("activeJob", activeJobs.find(incident.getActiveJobId()));
			s.put("waitingJobs", activeJobs.waitingForIncident(incidentId));
			return s;
		});
		return streamHub.subscribe(incidentId, snapshot);
	}
}
