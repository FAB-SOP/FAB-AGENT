package com.fabcontext.api.domain.processdata;

import com.fabcontext.api.auth.AuthUser;
import com.fabcontext.api.common.ApiResponse;
import com.fabcontext.api.domain.processdata.ProcessDataDtos.ChangePage;
import com.fabcontext.api.domain.processdata.ProcessDataDtos.Comparison;
import com.fabcontext.api.domain.processdata.ProcessDataDtos.MetrologyPage;
import com.fabcontext.api.domain.processdata.ProcessDataDtos.RawData;
import com.fabcontext.api.domain.processdata.ProcessDataDtos.Route;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
public class ProcessDataController {

	private final ProcessDataService service;

	@Tag(name = "incidents")
	@Operation(operationId = "getSensorData", summary = "raw 센서 조회")
	@GetMapping("/api/incidents/{incidentId}/sensor-data")
	public ApiResponse<RawData> getSensorData(@AuthenticationPrincipal AuthUser user, @PathVariable String incidentId,
			@RequestParam(required = false) String from, @RequestParam(required = false) String to,
			@RequestParam(required = false) String sensorId, @RequestParam(required = false) String recipeStep,
			@RequestParam(required = false) Integer limit, @RequestParam(required = false) Boolean includeBaseline,
			@RequestParam(required = false) String baselineRef) {
		return ApiResponse.of(service.sensorData(incidentId, user, from, to, sensorId, recipeStep, limit,
			includeBaseline, baselineRef));
	}

	@Tag(name = "incidents")
	@Operation(operationId = "getMetrology", summary = "증착 후 · 식각 후 · 연마 후 계측 조회")
	@GetMapping("/api/incidents/{incidentId}/metrology")
	public ApiResponse<MetrologyPage> getMetrology(@AuthenticationPrincipal AuthUser user,
			@PathVariable String incidentId, @RequestParam(required = false) String stage,
			@RequestParam(required = false) String featureId, @RequestParam(required = false) String siteId) {
		return ApiResponse.of(service.metrology(incidentId, user, stage, featureId, siteId));
	}

	@Tag(name = "wafers")
	@Operation(operationId = "getRoute", summary = "웨이퍼 공정 경로")
	@GetMapping("/api/wafers/{waferId}/route")
	public ApiResponse<Route> getRoute(@AuthenticationPrincipal AuthUser user, @PathVariable String waferId,
			@RequestParam(required = false) String layerId) {
		return ApiResponse.of(service.route(waferId, user, layerId));
	}

	@Tag(name = "incidents")
	@Operation(operationId = "getChanges", summary = "정비·설정·재료 변경 이력")
	@GetMapping("/api/incidents/{incidentId}/changes")
	public ApiResponse<ChangePage> getChanges(@AuthenticationPrincipal AuthUser user, @PathVariable String incidentId,
			@RequestParam(required = false) String from, @RequestParam(required = false) String to,
			@RequestParam(required = false) String type) {
		return ApiResponse.of(service.changes(incidentId, user, from, to, type));
	}

	@Tag(name = "incidents")
	@Operation(operationId = "getComparisons", summary = "저장된 공정 비교 근거")
	@GetMapping("/api/incidents/{incidentId}/comparisons")
	public ApiResponse<Comparison> getComparisons(@AuthenticationPrincipal AuthUser user,
			@PathVariable String incidentId, @RequestParam(required = false) String scope,
			@RequestParam(required = false) String processCode) {
		return ApiResponse.of(service.comparisons(incidentId, user, scope, processCode));
	}
}
