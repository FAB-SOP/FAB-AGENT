package com.fabcontext.api.internal.query;

import java.util.Map;

import com.fabcontext.api.common.ApiResponse;
import com.fabcontext.api.internal.InternalApi;
import io.swagger.v3.oas.annotations.Hidden;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 업무 MCP 읽기 도구 — 협업 · 기록, 재개 값 조회(확인 요청 하나) */
@Hidden
@RestController
@RequestMapping("/internal")
@RequiredArgsConstructor
class InternalCollabController implements InternalApi {

	private final InternalCollabQuery query;

	@GetMapping("/processes/{processCode}/owners")
	ApiResponse<Map<String, Object>> owners(@PathVariable String processCode) {
		return ApiResponse.of(query.owners(processCode));
	}

	@GetMapping("/incidents/{incidentId}/check-requests")
	ApiResponse<Map<String, Object>> checkRequests(@PathVariable String incidentId) {
		return ApiResponse.of(query.checkRequests(incidentId));
	}

	@GetMapping("/incidents/{incidentId}/actions")
	ApiResponse<Map<String, Object>> actions(@PathVariable String incidentId) {
		return ApiResponse.of(query.actions(incidentId));
	}

	@GetMapping("/incidents/{incidentId}/report-context")
	ApiResponse<Map<String, Object>> reportContext(@PathVariable String incidentId) {
		return ApiResponse.of(query.reportContext(incidentId));
	}

	@GetMapping("/check-requests/{requestId}")
	ApiResponse<Map<String, Object>> checkRequest(@PathVariable String requestId) {
		return ApiResponse.of(query.checkRequest(requestId));
	}
}
