package com.fabcontext.api.internal.query;

import java.util.Map;

import com.fabcontext.api.common.ApiResponse;
import com.fabcontext.api.internal.InternalApi;
import io.swagger.v3.oas.annotations.Hidden;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 업무 MCP 읽기 도구 — 사건 */
@Hidden
@RestController
@RequestMapping("/internal")
@RequiredArgsConstructor
class InternalIncidentController implements InternalApi {

	private final InternalIncidentQuery query;

	@GetMapping("/incidents/{incidentId}")
	ApiResponse<Map<String, Object>> incident(@PathVariable String incidentId) {
		return ApiResponse.of(query.incident(incidentId));
	}

	@GetMapping("/incidents")
	ApiResponse<Map<String, Object>> incidents(@RequestParam(required = false) String processCode,
			@RequestParam(required = false, defaultValue = "ALL") String status) {
		return ApiResponse.of(query.incidents(processCode, status));
	}
}
