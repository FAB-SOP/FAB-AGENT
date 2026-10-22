package com.fabcontext.api.internal.query;

import static com.fabcontext.api.internal.view.InternalViews.iso;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import com.fabcontext.api.domain.processdata.ChangeRecord;
import com.fabcontext.api.domain.processdata.PackStore;
import com.fabcontext.api.domain.processdata.PackStore.Trace;
import com.fabcontext.api.domain.processdata.ProcessDataRepositories.ChangeRecordRepository;
import com.fabcontext.api.domain.processdata.ProcessDataRepositories.RouteRunRepository;
import com.fabcontext.api.domain.processdata.ProcessEvidenceService;
import com.fabcontext.api.domain.processdata.RouteRun;
import com.fabcontext.api.domain.processdata.SegmentDetector;
import com.fabcontext.api.internal.DemoFaults;
import com.fabcontext.api.internal.InternalError;
import com.fabcontext.api.internal.view.InternalViews;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 업무 MCP 공정 데이터 도구 — get_wafer_lineage · get_run_and_metrology · get_run_features · get_sensor_trace ·
 * get_frozen_limits · get_change_events. 데이터 도구 이름 · 조회 순서는 데이터 팩의 Agent 계약(agent-contract.json)을 따른다.
 * 분석 시점(팩의 analysisAsOf) 이후 공개된 계측 · run · 정비 기록은 돌려주지 않는다 — 서버가 강제한다.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class InternalProcessQuery {

	private final RouteRunRepository routeRepository;
	private final ChangeRecordRepository changeRepository;
	private final ProcessEvidenceService evidence;
	private final PackStore pack;
	private final DemoFaults faults;
	private final InternalViews views;

	/** get_wafer_lineage — 웨이퍼의 실제 공정 경로. PM 이름이 같다고 앞뒤 장비를 연결하지 않는다 */
	public Map<String, Object> lineage(String waferId) {
		faults.maybeFail("get_wafer_lineage");
		List<RouteRun> runs = routeRepository.findByWaferIdOrderBySequenceNoAsc(waferId);
		if (runs.isEmpty()) {
			throw InternalError.notFound("웨이퍼 " + waferId + "가 없습니다.", "예: W-CURRENT-009-3 (로트 CURRENT-L009의 3번째 웨이퍼)");
		}
		List<Map<String, Object>> steps = runs.stream().map(r -> {
			Map<String, Object> m = new LinkedHashMap<>();
			m.put("sequenceNo", r.getSequenceNo());
			m.put("processCode", r.getProcessCode());
			m.put("equipmentId", r.getEquipmentId());
			m.put("runId", r.getRunId());
			m.put("upstreamRunId", r.getUpstreamRunId());
			m.put("recipeId", r.getRecipeId());
			m.put("startedAt", iso(r.getStartedAt()));
			m.put("endedAt", iso(r.getEndedAt()));
			m.put("metrologyAvailableAt", iso(r.getMetrologyAvailableAt()));
			return m;
		}).toList();
		Map<String, Object> data = new LinkedHashMap<>();
		data.put("waferId", waferId);
		data.put("lotId", runs.getFirst().getLotId());
		data.put("phase", runs.getFirst().getPhase());
		data.put("path", String.join(" → ", runs.stream().map(r -> r.getProcessCode() + ":" + r.getEquipmentId()).toList()));
		data.put("steps", steps);
		return views.env(data, List.of(views.ref("E-LIN-" + waferId, "ROUTE", waferId)), List.of());
	}

	/** get_run_and_metrology — run · 5위치 계측 · 평균 · 증착/제거량 · 관리한계 R1 · 정상 분포 대비 편차(서버 계산) */
	public Map<String, Object> run(String runId) {
		faults.maybeFail("get_run_and_metrology");
		RouteRun r = loadRun(runId);
		Map<String, Object> ev = evidence.runEvidence(r, views.asOf());
		List<String> warnings = new ArrayList<>();
		if (!Boolean.TRUE.equals(ev.get("available"))) {
			warnings.add(String.valueOf(ev.get("unavailableReason")));
		}
		return views.env(ev, List.of(views.ref("E-RUN-" + runId, "METROLOGY", runId)), warnings);
	}

	/** get_run_features — 센서 본 처리 평균 · 참고 범위 이탈 · 설정값 차이 · 같은 PM 정상 run 대비 편차 */
	public Map<String, Object> features(String runId, List<String> sensorIds) {
		faults.maybeFail("get_run_features");
		RouteRun r = loadRun(runId);
		requireRunEnded(r);
		List<Map<String, Object>> items = evidence.featureEvidence(r, sensorIds);
		if (sensorIds != null && items.isEmpty()) {
			throw InternalError.notFound("run " + runId + "에 센서 " + sensorIds + " 특징이 없습니다.", "가능한 센서: " + sensorList(r));
		}
		Map<String, Object> data = new LinkedHashMap<>();
		data.put("runId", runId);
		data.put("processCode", r.getProcessCode());
		data.put("equipmentId", r.getEquipmentId());
		data.put("scope", "RUN_PROCESS_STEADY_MEAN");
		data.put("items", items);
		data.put("outsideReferenceBand", items.stream().filter(f -> Boolean.TRUE.equals(f.get("outsideReferenceBand")))
			.map(f -> f.get("sensorId")).toList());
		return views.env(data, List.of(views.ref("E-FEAT-" + runId, "SENSOR", runId)),
			List.of("참고 범위는 BASELINE run 평균의 분포이며 원시 점 단위 SPC가 아닙니다. 센서 하나와 고장을 일대일로 대응시키지 않습니다."));
	}

	/** get_sensor_trace — 원시 시계열을 구간 평균으로 줄여 돌려준다(최대 MAX_TRACE_POINTS점) */
	public Map<String, Object> trace(String runId, String sensorId, String step, Integer maxPoints) {
		faults.maybeFail("get_sensor_trace");
		RouteRun run = loadRun(runId);
		requireRunEnded(run);
		Trace trace = pack.trace(runId, sensorId).orElseThrow(() -> InternalError.notFound(
			"run " + runId + "의 " + sensorId + " 원시 시계열이 없습니다.",
			"BASELINE run은 원시 시계열 없이 특징만 있습니다(get_run_features). 가능한 센서: " + sensorList(run)));
		int pointCount = Math.max(10, Math.min(InternalViews.MAX_TRACE_POINTS, maxPoints == null ? 60 : maxPoints));
		List<double[]> picked = pickSamples(run, trace, step);
		Map<String, Object> data = traceSummary(run, trace, runId, sensorId, step, picked, pointCount);
		// 지속 편차 구간(DEMO-SEGMENT-V2) — 시계열 참조 범위(sensorEnvelope)로 찾은 관측. 고장 시점 · 원인 확정이 아니다
		Map<String, Object> envelope = pack.envelope(run.getProcessCode(), sensorId).orElse(null);
		List<Map<String, Object>> segments = SegmentDetector.detect(run, trace, envelope);
		data.put("sensorEnvelope", envelope);
		data.put("segments", segments);
		data.put("segmentRule", Map.of("ruleVersion", SegmentDetector.RULE_VERSION, "timeBase", "run 시작(t0)부터 잰 초",
			"note", "PROCESS 처음 2초 · READY · END 제외, 2초 이동평균이 sensorEnvelope 밖으로 5초 이상 — 4σ · 2초 · 5초는 시연 선택값"));
		return views.env(data, traceRefs(runId, sensorId, segments), traceWarnings(envelope));
	}

	/** 고른 단계(없으면 전체)의 결측 아닌 원시 점 — [ms, value] */
	private static List<double[]> pickSamples(RouteRun run, Trace trace, String step) {
		long readyMs = run.getReadySeconds() * 1000L;
		long processEndMs = readyMs + run.getProcessSeconds() * 1000L;
		List<double[]> picked = new ArrayList<>();
		for (int i = 0; i < trace.values().length; i++) {
			long ms = i * trace.intervalMs();
			String phase = ms < readyMs ? "READY" : ms < processEndMs ? "PROCESS" : "END";
			if ((step == null || step.equalsIgnoreCase(phase)) && !Double.isNaN(trace.values()[i])) {
				picked.add(new double[] {ms, trace.values()[i]});
			}
		}
		return picked;
	}

	/** 원시 점을 최대 pointCount개의 구간 평균으로 줄인다 */
	private static List<Map<String, Object>> downsample(List<double[]> picked, int pointCount) {
		List<Map<String, Object>> points = new ArrayList<>();
		int bucket = Math.max(1, (int) Math.ceil(picked.size() / (double) pointCount));
		for (int i = 0; i < picked.size(); i += bucket) {
			List<double[]> part = picked.subList(i, Math.min(picked.size(), i + bucket));
			double mean = part.stream().mapToDouble(sample -> sample[1]).average().orElse(Double.NaN);
			points.add(Map.of("tMs", (long) part.getFirst()[0], "mean", ProcessEvidenceService.round(mean, 4), "n", part.size()));
		}
		return points;
	}

	private Map<String, Object> traceSummary(RouteRun run, Trace trace, String runId, String sensorId, String step,
			List<double[]> picked, int pointCount) {
		long readyMs = run.getReadySeconds() * 1000L;
		long processEndMs = readyMs + run.getProcessSeconds() * 1000L;
		List<Map<String, Object>> points = downsample(picked, pointCount);
		var stats = picked.stream().mapToDouble(sample -> sample[1]).summaryStatistics();
		Map<String, Object> data = new LinkedHashMap<>();
		data.put("runId", runId);
		data.put("sensorId", sensorId);
		data.put("unit", trace.unit());
		data.put("t0", iso(trace.t0()));
		data.put("sourceIntervalMs", trace.intervalMs());
		data.put("step", step == null ? "ALL" : step.toUpperCase());
		data.put("phases", Map.of("readyMs", readyMs, "processEndMs", processEndMs, "note", "PROCESS 시작 후 2초는 초기 전이"));
		data.put("setpointProfile", trace.setpointProfile());
		data.put("sampleCount", picked.size());
		data.put("summary", picked.isEmpty() ? Map.of() : Map.of("mean", ProcessEvidenceService.round(stats.getAverage(), 4),
			"min", stats.getMin(), "max", stats.getMax()));
		data.put("points", points);
		data.put("quality", trace.quality());
		return data;
	}

	private List<Map<String, Object>> traceRefs(String runId, String sensorId, List<Map<String, Object>> segments) {
		List<Map<String, Object>> refs = new ArrayList<>(List.of(views.ref("E-TRC-" + runId + "-" + sensorId, "SENSOR", runId + ":" + sensorId)));
		if (!segments.isEmpty()) {
			refs.add(views.ref("E-SEG-" + runId + "-" + sensorId, "SENSOR", runId + ":" + sensorId + ":segments"));
		}
		return refs;
	}

	private static List<String> traceWarnings(Map<String, Object> envelope) {
		List<String> warnings = new ArrayList<>(List.of("구간 평균으로 줄인 값입니다. 원시 점은 독립된 정상 run이 아니며, 없는 고속 값을 보간하지 않았습니다."));
		if (envelope != null) {
			warnings.add("구간은 sensorEnvelope(center ± 4σ, 2초 이동평균, 5초 지속 — 팀 시연 선택값, calibratedForRealTool=false)로 찾은 관측입니다. "
					+ "고장 발생 시점이나 원인 확정이 아닙니다.");
		}
		return warnings;
	}

	/** get_frozen_limits — 동결 관리한계(I, R1)와 센서 특징 참고 범위 */
	public Map<String, Object> limits(String processCode) {
		faults.maybeFail("get_frozen_limits");
		List<Map<String, Object>> limits = pack.limits().stream()
			.filter(l -> processCode == null || processCode.equals(l.get("process"))).toList();
		if (limits.isEmpty()) {
			throw InternalError.invalid("processCode는 CVD · ETCH · CMP 중 하나입니다.", "");
		}
		Map<String, Object> data = new LinkedHashMap<>();
		data.put("qualityLimits", limits);
		data.put("featureReferenceBands", pack.sensors().stream()
			.filter(s -> processCode == null || processCode.equals(s.get("process")))
			.map(s -> pack.band(String.valueOf(s.get("process")), String.valueOf(s.get("sensorId"))).orElse(null))
			.filter(Objects::nonNull).toList());
		data.put("sensorCatalog", pack.sensors().stream()
			.filter(s -> processCode == null || processCode.equals(s.get("process"))).toList());
		String key = processCode == null ? "ALL" : processCode;
		return views.env(data, List.of(views.ref("E-LIM-" + key, "METROLOGY", key)),
			List.of("관리한계는 BASELINE으로만 정했고 현재 · 후속 결과로 넓히지 않았습니다. 등록 규칙은 R1뿐이라 "
					+ "'어떤 규칙으로도 검출되지 않았다'고 말하지 않습니다. 센서 USL/LSL은 등록하지 않았습니다."));
	}

	/** get_change_events — 정비 · 설정 변경 이력 (분석 시점 이전에 기록된 것만) */
	public Map<String, Object> changes(String equipmentId) {
		faults.maybeFail("get_change_events");
		Instant asOf = views.asOf();
		List<Map<String, Object>> rows = changeRepository.findAll().stream()
			.filter(c -> equipmentId == null || equipmentId.equals(c.getEquipmentId()))
			.filter(c -> c.getRecordedAt() == null || !c.getRecordedAt().isAfter(asOf))
			.sorted(Comparator.comparing(ChangeRecord::getOccurredAt).reversed())
			.map(c -> {
				Map<String, Object> m = new LinkedHashMap<>();
				m.put("changeId", c.getChangeId());
				m.put("processCode", c.getProcessCode());
				m.put("equipmentId", c.getEquipmentId());
				m.put("type", c.getChangeType());
				m.put("occurredAt", iso(c.getOccurredAt()));
				m.put("availableAt", iso(c.getRecordedAt()));
				m.put("description", c.getSummary());
				m.put("rootCauseConfirmed", c.isRootCauseConfirmed());
				return m;
			}).toList();
		String key = equipmentId == null ? "ALL" : equipmentId;
		return views.env(Map.of("items", rows.stream().limit(InternalViews.MAX_ITEMS).toList(), "truncated", rows.size() > InternalViews.MAX_ITEMS),
			List.of(views.ref("E-CHG-" + key, "CHANGE", key)),
			List.of("정비 기록이 있다는 것만으로 원인이 확정되지 않습니다(rootCauseConfirmed)."));
	}

	private RouteRun loadRun(String runId) {
		RouteRun r = routeRepository.findByRunId(runId).orElseThrow(() -> InternalError.notFound("run " + runId + "가 없습니다.",
			"예: RUN-W-CURRENT-009-3-CMP — get_wafer_lineage로 웨이퍼의 runId를 먼저 확인하세요."));
		if (r.getStartedAt().isAfter(views.asOf())) {
			throw new InternalError("FORBIDDEN", "run " + runId + "은 분석 시점(" + iso(views.asOf()) + ") 이후에 시작했습니다.",
				"분석 시점 이후 자료(FOLLOWUP 등)는 현재 판단에 쓰지 않습니다.");
		}
		return r;
	}

	private void requireRunEnded(RouteRun r) {
		if (r.getEndedAt().isAfter(views.asOf())) {
			throw new InternalError("FORBIDDEN", "run " + r.getRunId() + "이 분석 시점에 끝나지 않았습니다.", "");
		}
	}

	private String sensorList(RouteRun r) {
		return String.join(", ", pack.sensors().stream().filter(s -> r.getProcessCode().equals(s.get("process")))
			.map(s -> String.valueOf(s.get("sensorId"))).toList());
	}
}
