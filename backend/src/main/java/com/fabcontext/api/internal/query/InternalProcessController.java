package com.fabcontext.api.internal.query;

import java.util.Map;

import com.fabcontext.api.common.ApiResponse;
import com.fabcontext.api.internal.InternalApi;
import com.fabcontext.api.internal.view.InternalViews;
import io.swagger.v3.oas.annotations.Hidden;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 업무 MCP 읽기 도구 — 데이터 팩 계약(dataMcpTools) */
@Hidden
@RestController
@RequestMapping("/internal")
@RequiredArgsConstructor
class InternalProcessController implements InternalApi {

	private final InternalProcessQuery query;

	@GetMapping("/wafers/{waferId}/lineage")
	ApiResponse<Map<String, Object>> lineage(@PathVariable String waferId) {
		return ApiResponse.of(query.lineage(waferId));
	}

	@GetMapping("/runs/{runId}")
	ApiResponse<Map<String, Object>> run(@PathVariable String runId) {
		return ApiResponse.of(query.run(runId));
	}

	@GetMapping("/runs/{runId}/features")
	ApiResponse<Map<String, Object>> features(@PathVariable String runId, @RequestParam(required = false) String sensorIds) {
		return ApiResponse.of(query.features(runId, InternalViews.csv(sensorIds)));
	}

	@GetMapping("/runs/{runId}/traces/{sensorId}")
	ApiResponse<Map<String, Object>> trace(@PathVariable String runId, @PathVariable String sensorId,
			@RequestParam(required = false) String step, @RequestParam(required = false) Integer maxPoints) {
		return ApiResponse.of(query.trace(runId, sensorId, step, maxPoints));
	}

	@GetMapping("/limits")
	ApiResponse<Map<String, Object>> limits(@RequestParam(required = false) String processCode) {
		return ApiResponse.of(query.limits(processCode));
	}

	@GetMapping("/changes")
	ApiResponse<Map<String, Object>> changes(@RequestParam(required = false) String equipmentId) {
		return ApiResponse.of(query.changes(equipmentId));
	}
}
