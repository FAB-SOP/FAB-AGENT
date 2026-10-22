package com.fabcontext.api.domain.report;

import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.fabcontext.api.common.ApiException;
import com.fabcontext.api.domain.processdata.PackStore;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

/**
 * 원인 분류 검사 — 직접 확정과 승인 경로가 같은 규칙을 쓴다.
 * 장비 파라미터는 공정 대시보드 센서 시계열과 같은 출처(데이터 팩 센서 카탈로그)의 그 공정 센서만 허용한다.
 */
@Component
@RequiredArgsConstructor
public class CauseClassificationPolicy {

	private final PackStore pack;

	/** 검사 · 정규화한 분류를 만든다. MACHINE이 아니면 장비 파라미터는 버린다. */
	public ReportCauseClassification validate(CauseClassificationInput input, String processCode, String actorId,
			Instant at) {
		ReportCauseCategory category = parseCategory(input);
		List<String> equipmentParameters = category.requiresEquipmentParameters()
				? normalizeEquipmentParameters(input.equipmentParameters(), processCode)
				: List.of();
		return ReportCauseClassification.of(category, equipmentParameters, actorId, at);
	}

	/** 그 공정의 센서 ID — 센서 카탈로그 순서 */
	public List<String> equipmentParametersOf(String processCode) {
		return pack.sensors().stream()
			.filter(sensor -> processCode.equals(sensor.get("process")))
			.map(sensor -> String.valueOf(sensor.get("sensorId")))
			.toList();
	}

	private static ReportCauseCategory parseCategory(CauseClassificationInput input) {
		if (input == null || input.category() == null || input.category().isBlank()) {
			throw ApiException.unprocessable("CAUSE_CLASSIFICATION_REQUIRED",
				"원인 분류(classification.category)를 선택해야 확정할 수 있습니다.");
		}
		try {
			return ReportCauseCategory.valueOf(input.category());
		}
		catch (IllegalArgumentException unknownCategory) {
			throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "INVALID_CAUSE_CATEGORY",
				"원인 분류는 MATERIAL · MAN · METHOD · MACHINE · HOLD 중 하나여야 합니다.",
				Map.of("category", input.category(), "allowed", List.of(ReportCauseCategory.values())));
		}
	}

	private List<String> normalizeEquipmentParameters(List<String> requested, String processCode) {
		if (requested == null || requested.isEmpty()) {
			throw ApiException.unprocessable("EQUIPMENT_PARAMETER_REQUIRED",
				"원인 분류가 MACHINE이면 장비 파라미터를 하나 이상 선택해야 합니다.");
		}
		List<String> allowed = equipmentParametersOf(processCode);
		Set<String> requestedSet = new LinkedHashSet<>(requested);
		List<String> unknown = requestedSet.stream().filter(parameter -> !allowed.contains(parameter)).toList();
		if (!unknown.isEmpty()) {
			throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "INVALID_EQUIPMENT_PARAMETER",
				processCode + " 공정의 센서만 장비 파라미터로 고를 수 있습니다.",
				Map.of("invalid", unknown, "allowed", allowed));
		}
		return allowed.stream().filter(requestedSet::contains).toList();
	}
}
