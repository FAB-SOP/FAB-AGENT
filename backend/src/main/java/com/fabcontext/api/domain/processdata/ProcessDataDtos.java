package com.fabcontext.api.domain.processdata;

import java.time.Instant;
import java.util.List;
import java.util.Map;

public final class ProcessDataDtos {

	private ProcessDataDtos() {
	}

	public record Sample(String sensorId, String sensorName, String runId, String waferId, String equipmentId,
			String chamberId, String recipeStep, Instant measuredAt, Double value, String unit, String quality) {
	}

	public record RawQuery(Instant from, Instant to, String sensorId, String recipeStep, int limit,
			boolean includeBaseline, String baselineRef) {
	}

	/**
	 * 정상 기준 — 원시 점의 기준선이 아니라 BASELINE run 본 처리 평균의 참고 범위다(점 단위 SPC 아님).
	 * BASELINE 원시 시계열은 팩에 없다.
	 */
	/** 정상 기준 trace — 프론트 계약 Baseline. 팩에 기준 trace가 없어 지금은 늘 null이다(baselineUnavailableReason) */
	public record Baseline(String baselineRef, Map<String, Object> matchedConditions, List<Sample> samples) {
	}

	/** 원본 센서 응답 — 팩의 원시 시계열(data/sensor-traces.jsonl.gz)에서 꺼낸다. baseline=null이면 비교 기준이 없다. */
	public record RawData(String incidentId, RawQuery query, List<Sample> samples, int totalCount,
			boolean truncated, Map<String, Long> qualitySummary, Baseline baseline, String baselineUnavailableReason,
			String rawRef, String dataVersion) {
	}

	public record MetrologyItem(String measurementId, String stage, String lotId, String waferId, String runId,
			String layerId, String featureId, String siteId, String specId, double value, String unit,
			String toolId, Instant measuredAt) {

		static MetrologyItem from(Metrology m) {
			return new MetrologyItem(m.getMeasurementId(), m.getStage(), m.getLotId(), m.getWaferId(), m.getRunId(),
				m.getLayerId(), m.getFeatureId(), m.getSiteId(), m.getSpecId(), m.getValue(), m.getUnit(),
				m.getToolId(), m.getMeasuredAt());
		}
	}

	public record MetrologyPage(String incidentId, List<MetrologyItem> items, int totalCount, String dataVersion,
			String specNote) {
	}

	public record RunView(int sequenceNo, String runId, String processCode, String stepName, String equipmentId,
			String chamberId, String recipeId, String recipeVersion, String layerId, Instant startedAt,
			Instant endedAt) {

		static RunView from(RouteRun r) {
			return new RunView(r.getSequenceNo(), r.getRunId(), r.getProcessCode(), r.getStepName(),
				r.getEquipmentId(), r.getChamberId(), r.getRecipeId(), r.getRecipeVersion(), r.getLayerId(),
				r.getStartedAt(), r.getEndedAt());
		}
	}

	public record Route(String waferId, String lotId, String layerId, List<RunView> runs, String dataVersion) {
	}

	public record ChangeItem(String changeId, String relation, String processCode, String equipmentId,
			String chamberId, String changeType, String summary, String before, String after, Instant occurredAt,
			Instant recordedAt, String recordedBy, List<String> evidenceIds) {

		static ChangeItem from(ChangeRecord c, String relation) {
			return new ChangeItem(c.getChangeId(), relation, c.getProcessCode(), c.getEquipmentId(), c.getChamberId(),
				c.getChangeType(), c.getSummary(), c.getBefore(), c.getAfter(), c.getOccurredAt(), c.getRecordedAt(),
				c.getRecordedBy(), List.copyOf(c.getEvidenceIds()));
		}
	}

	public record ChangePage(String incidentId, Instant from, Instant to, List<ChangeItem> items, String dataVersion) {
	}

	/**
	 * 공정 비교 — 사건 웨이퍼의 한 공정 단계 증거(5위치 두께 · 증착/제거량 · 관리한계 R1 · 정상 분포 대비 편차)와
	 * 그 run의 센서 특징(참고 범위 이탈 · 설정값 차이). scope=CURRENT는 사건 공정, UPSTREAM은 앞 공정(processCode).
	 */
	public record Comparison(String incidentId, String scope, String processCode, String runId, String equipmentId,
			Map<String, Object> evidence, List<Map<String, Object>> features, String dataVersion, Instant asOf) {
	}
}
