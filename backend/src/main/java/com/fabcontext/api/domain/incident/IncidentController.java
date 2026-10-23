package com.fabcontext.api.domain.incident;

import com.fabcontext.api.auth.AuthUser;
import com.fabcontext.api.auth.SecurityConfig;
import com.fabcontext.api.common.ApiResponse;
import com.fabcontext.api.common.PageResponse;
import com.fabcontext.api.domain.incident.IncidentDtos.IncidentDetail;
import com.fabcontext.api.domain.incident.IncidentDtos.IncidentSummary;
import com.fabcontext.api.domain.incident.IncidentDtos.IncidentTransition;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "incidents")
@RestController
@RequestMapping("/api/incidents")
@RequiredArgsConstructor
public class IncidentController {

	private final IncidentService incidentService;

	@Operation(operationId = "listIncidents", summary = "사건 목록")
	@GetMapping
	public ApiResponse<PageResponse<IncidentSummary>> listIncidents(@AuthenticationPrincipal AuthUser user,
			@RequestParam(required = false) String q, @RequestParam(required = false) String status,
			@RequestParam(required = false) String jobStatus, @RequestParam(required = false) String processCode,
			@RequestParam(required = false) String cursor, @RequestParam(required = false) Integer size) {
		return ApiResponse.of(incidentService.list(user, q, status, jobStatus, processCode, cursor, size));
	}

	@Operation(operationId = "getIncident", summary = "사건 상세")
	@GetMapping("/{incidentId}")
	public ApiResponse<IncidentDetail> getIncident(@AuthenticationPrincipal AuthUser user,
			@PathVariable String incidentId) {
		return ApiResponse.of(incidentService.detail(incidentId, user));
	}

	@Operation(operationId = "transitionIncident", summary = "사건 상태 전이 — 제거됨 (항상 409)",
		description = "사건 종료는 보고서 확정으로만 일어나며 관리자의 수동 보류 · 보류 해제 · 종료는 없다. MANAGER · 버전 검사 뒤 모든 targetStatus를 409 TRANSITION_REMOVED로 거부한다(호환용 경로).")
	@PostMapping("/{incidentId}/transitions")
	public ApiResponse<IncidentDetail> transitionIncident(@AuthenticationPrincipal AuthUser user,
			@PathVariable String incidentId, @RequestHeader(SecurityConfig.CSRF_HEADER) String csrf,
			@Valid @RequestBody IncidentTransition body) {
		return ApiResponse.of(incidentService.transition(incidentId, user, body));
	}
}
