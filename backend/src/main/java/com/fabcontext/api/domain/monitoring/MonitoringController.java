package com.fabcontext.api.domain.monitoring;

import java.util.Map;

import com.fabcontext.api.auth.AuthUser;
import com.fabcontext.api.common.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 모니터링 GET 3개 — 정상 LOT · PM 조회 (프론트 계약 data/backend-handoff-v2/combined.openapi.json의 모니터링 경로, 응답 예시 backend-handoff-v2/examples) */
@Tag(name = "monitoring")
@RestController
@RequiredArgsConstructor
public class MonitoringController {

	private final MonitoringService service;

	@Operation(operationId = "listMonitoringLots", summary = "공정의 현재 LOT 목록")
	@GetMapping("/api/monitoring/lots")
	public ApiResponse<Map<String, Object>> lots(@AuthenticationPrincipal AuthUser user,
			@RequestParam(required = false) String processCode) {
		return ApiResponse.of(service.lots(user, processCode));
	}

	@Operation(operationId = "getMonitoringSummary", summary = "LOT · 공정 상세 — PM별 run · 5위치 계측 · 특징 · 기준")
	@GetMapping("/api/monitoring/lots/{lotId}/summary")
	public ApiResponse<Map<String, Object>> summary(@AuthenticationPrincipal AuthUser user, @PathVariable String lotId,
			@RequestParam(required = false) String processCode) {
		return ApiResponse.of(service.summary(user, lotId, processCode));
	}

	@Operation(operationId = "getRunSensorData", summary = "run 하나 · 센서 하나의 원시 시계열")
	@GetMapping("/api/monitoring/runs/{runId}/sensor-data")
	public ApiResponse<Map<String, Object>> sensorData(@AuthenticationPrincipal AuthUser user, @PathVariable String runId,
			@RequestParam(required = false) String sensorId, @RequestParam(required = false) Integer limit) {
		return ApiResponse.of(service.sensorData(user, runId, sensorId, limit));
	}
}
