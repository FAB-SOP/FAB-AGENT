package com.fabcontext.api.domain.events;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/** 센서 · 계측 서비스 시뮬레이터가 보내는 이상 이벤트. eventId로 중복 접수를 막는다. */
public record AnomalyEvent(@NotBlank @Size(max = 120) String eventId, @Size(max = 60) String source,
		@NotBlank @Size(max = 60) String anomalyType, @Pattern(regexp = "LOW|MEDIUM|HIGH|CRITICAL") String severity,
		@NotBlank @Size(max = 30) String processCode, @NotBlank @Size(max = 60) String equipmentId,
		@Size(max = 60) String chamberId, @Size(max = 60) String lotId, List<String> waferIds, List<String> runIds,
		List<String> sensorIds, @NotNull Instant detectedAt, @Size(max = 200) String title,
		@Size(max = 2000) String summary, Map<String, Object> metrics) {
}
