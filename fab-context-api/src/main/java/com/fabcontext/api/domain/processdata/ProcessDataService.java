package com.fabcontext.api.domain.processdata;

import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Collectors;

import com.fabcontext.api.auth.AuthUser;
import com.fabcontext.api.common.ApiException;
import com.fabcontext.api.domain.incident.Incident;
import com.fabcontext.api.domain.incident.IncidentRepository;
import com.fabcontext.api.domain.incident.IncidentService;
import com.fabcontext.api.domain.processdata.PackStore.Trace;
import com.fabcontext.api.domain.processdata.ProcessDataDtos.Baseline;
import com.fabcontext.api.domain.processdata.ProcessDataDtos.ChangeItem;
import com.fabcontext.api.domain.processdata.ProcessDataDtos.ChangePage;
import com.fabcontext.api.domain.processdata.ProcessDataDtos.Comparison;
import com.fabcontext.api.domain.processdata.ProcessDataDtos.MetrologyItem;
import com.fabcontext.api.domain.processdata.ProcessDataDtos.MetrologyPage;
import com.fabcontext.api.domain.processdata.ProcessDataDtos.RawData;
import com.fabcontext.api.domain.processdata.ProcessDataDtos.RawQuery;
import com.fabcontext.api.domain.processdata.ProcessDataDtos.Route;
import com.fabcontext.api.domain.processdata.ProcessDataDtos.RunView;
import com.fabcontext.api.domain.processdata.ProcessDataDtos.Sample;
import com.fabcontext.api.domain.processdata.ProcessDataRepositories.ChangeRecordRepository;
import com.fabcontext.api.domain.processdata.ProcessDataRepositories.MetrologyRepository;
import com.fabcontext.api.domain.processdata.ProcessDataRepositories.RouteRunRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 화면(P04~P06) 공정 데이터 — 센서 원시 시계열 · 5위치 계측 · 웨이퍼 경로 · 정비 이력 · 공정 비교 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ProcessDataService {

	/** 공정 → 그 공정 직후 계측 단계 */
	public static final Map<String, String> STAGE_OF = ProcessEvidenceService.STAGE_OF;
	public static final List<String> PROCESS_ORDER = ProcessEvidenceService.PROCESS_ORDER;
	private static final Set<String> STAGES = Set.copyOf(STAGE_OF.values());

	/** 기본 5000 — 지금 데이터는 센서 한 run이 최대 3201점이라 한 번에 잘리지 않는다(프론트는 truncated=true면 구간 판정을 생략한다) */
	public static final int DEFAULT_LIMIT = 5000;
	public static final int MAX_LIMIT = 5000;

	private final IncidentService incidentService;
	private final IncidentRepository incidentRepository;
	private final MetrologyRepository metrologyRepository;
	private final RouteRunRepository routeRepository;
	private final ChangeRecordRepository changeRepository;
	private final ProcessEvidenceService evidence;
	private final PackStore pack;
	private final DataVersion dataVersion;

	public RawData sensorData(String incidentId, AuthUser user, String fromParam, String toParam, String sensorId,
			String recipeStep, Integer limitParam, Boolean includeBaselineParam, String baselineRefParam) {
		Incident incident = incidentService.loadForUser(incidentId, user);
		List<RouteRun> runs = incident.getRunIds().isEmpty() ? List.of() : routeRepository.findByRunIdIn(incident.getRunIds());
		Instant[] window = defaultSensorWindow(incident, runs);
		Instant from = parseTime("from", fromParam, window[0]);
		Instant to = parseTime("to", toParam, window[1]);
		if (!from.isBefore(to)) {
			throw ApiException.unprocessable("INVALID_TIME_RANGE", "from은 to보다 앞서야 합니다.");
		}
		int limit = limitParam == null ? DEFAULT_LIMIT : limitParam;
		if (limit < 1 || limit > MAX_LIMIT) {
			throw ApiException.badRequest("INVALID_LIMIT", "limit은 1~" + MAX_LIMIT + " 사이여야 합니다.");
		}
		List<Sample> samples = new ArrayList<>();
		for (RouteRun r : runs) {
			for (Trace t : pack.traces(r.getRunId())) {
				if (sensorId != null && !sensorId.equals(t.sensorId())) {
					continue;
				}
				for (Sample s : samples(r, t)) {
					if (s.measuredAt().isBefore(from) || s.measuredAt().isAfter(to)
							|| (recipeStep != null && !recipeStep.equals(s.recipeStep()))) {
						continue;
					}
					samples.add(s);
				}
			}
		}
		samples.sort(Comparator.comparing(Sample::measuredAt).thenComparing(Sample::sensorId));
		Map<String, Long> quality = samples.stream()
			.collect(Collectors.groupingBy(Sample::quality, TreeMap::new, Collectors.counting()));

		// 프론트 계약 Baseline {baselineRef, matchedConditions, samples}은 정상 기준 trace다. 팩에는 BASELINE 원시 시계열이 없다
		// (특징만 있다) — 그래서 null과 이유를 준다. 참고 분포(references) · 시계열 참조 범위(sensorEnvelopes)는 모니터링 상세에 있다
		Baseline baseline = null;
		String unavailable = Boolean.TRUE.equals(includeBaselineParam) ? NO_BASELINE_TRACE : null;
		String rawRef = "raw://sensor-traces/" + incidentId + "?from=" + from + "&to=" + to
				+ (sensorId == null ? "" : "&sensorId=" + sensorId) + (recipeStep == null ? "" : "&recipeStep=" + recipeStep);
		return new RawData(incidentId,
			new RawQuery(from, to, sensorId, recipeStep, limit, Boolean.TRUE.equals(includeBaselineParam), baselineRefParam),
			samples.stream().limit(limit).toList(), samples.size(), samples.size() > limit, quality, baseline, unavailable,
			rawRef, dataVersion.current());
	}

	public MetrologyPage metrology(String incidentId, AuthUser user, String stage, String featureId, String siteId) {
		Incident incident = incidentService.loadForUser(incidentId, user);
		if (stage != null && !STAGES.contains(stage)) {
			throw ApiException.badRequest("INVALID_STAGE", "stage는 AFTER_CVD · AFTER_ETCH · AFTER_CMP 중 하나입니다.");
		}
		Instant asOf = evidence.defaultAsOf();
		List<MetrologyItem> items = incident.getWaferIds().isEmpty() ? List.of()
			: metrologyRepository.findByWaferIdInOrderByWaferIdAscStageAscSiteIdAsc(incident.getWaferIds()).stream()
				.filter(m -> !m.getAvailableAt().isAfter(asOf))
				.filter(m -> stage == null || stage.equals(m.getStage()))
				.filter(m -> featureId == null || featureId.equals(m.getFeatureId()))
				.filter(m -> siteId == null || siteId.equals(m.getSiteId()))
				.map(MetrologyItem::from)
				.toList();
		return new MetrologyPage(incidentId, items, items.size(), dataVersion.current(),
			"한 장의 5위치(CENTER · NORTH · EAST · SOUTH · WEST) 값입니다. 관리한계 · 규격은 specId(QL-…)의 동결 기준으로 비교하며, "
					+ "5점만으로 전체 표면 형상 · 막질을 판정하지 않습니다.");
	}

	public Route route(String waferId, AuthUser user, String layerId) {
		List<RouteRun> runs = routeRepository.findByWaferIdOrderBySequenceNoAsc(waferId);
		if (runs.isEmpty()) {
			throw ApiException.notFound("wafer", waferId);
		}
		boolean runInScope = runs.stream().anyMatch(r -> user.hasScope(r.getProcessCode()));
		boolean incidentInScope = incidentRepository.findByProcessCodeInOrderByDetectedAtDesc(user.processScopes())
			.stream().anyMatch(i -> i.getWaferIds().contains(waferId));
		if (!runInScope && !incidentInScope) {
			throw ApiException.forbidden("PROCESS_SCOPE_DENIED", "이 웨이퍼 경로를 조회할 공정 권한이 없습니다.");
		}
		List<RunView> views = runs.stream()
			.filter(r -> layerId == null || layerId.equals(r.getLayerId()))
			.map(RunView::from)
			.toList();
		return new Route(waferId, runs.getFirst().getLotId(), layerId, views, dataVersion.current());
	}

	public ChangePage changes(String incidentId, AuthUser user, String fromParam, String toParam, String type) {
		Incident incident = incidentService.loadForUser(incidentId, user);
		Instant from = parseTime("from", fromParam, incident.getDetectedAt().minus(Duration.ofDays(14)));
		Instant to = parseTime("to", toParam, incident.getDetectedAt().plus(Duration.ofDays(1)));
		if (!from.isBefore(to)) {
			throw ApiException.unprocessable("INVALID_TIME_RANGE", "from은 to보다 앞서야 합니다.");
		}
		Set<String> types = type == null || type.isBlank() ? Set.of()
			: Set.of(type.toUpperCase().split("\\s*,\\s*"));
		Set<String> upstream = upstreamEquipment(incident);
		Set<String> equipment = new LinkedHashSet<>();
		equipment.add(incident.getEquipmentId());
		equipment.addAll(upstream);
		List<ChangeItem> items = changeRepository.findByEquipmentIdInOrderByOccurredAtDesc(equipment).stream()
			.filter(c -> !c.getOccurredAt().isBefore(from) && !c.getOccurredAt().isAfter(to))
			.filter(c -> types.isEmpty() || types.contains(c.getChangeType()))
			.map(c -> ChangeItem.from(c, c.getEquipmentId().equals(incident.getEquipmentId()) ? "CURRENT" : "UPSTREAM"))
			.toList();
		return new ChangePage(incidentId, from, to, items, dataVersion.current());
	}

	/** processCode를 주면 그 공정 단계, 없으면 CURRENT는 사건 공정 · UPSTREAM은 바로 앞 공정 */
	public Comparison comparisons(String incidentId, AuthUser user, String scopeParam, String processCodeParam) {
		Incident incident = incidentService.loadForUser(incidentId, user);
		String scope = scopeParam == null ? "CURRENT" : scopeParam.toUpperCase();
		if (!Set.of("CURRENT", "UPSTREAM").contains(scope)) {
			throw ApiException.badRequest("INVALID_SCOPE", "scope는 CURRENT 또는 UPSTREAM입니다.");
		}
		String proc = incident.getProcessCode();
		String target = processCodeParam == null ? null : processCodeParam.toUpperCase();
		if (target != null && !STAGE_OF.containsKey(target)) {
			throw ApiException.badRequest("INVALID_PROCESS_CODE", "processCode는 CVD · ETCH · CMP 중 하나입니다.");
		}
		if ("CURRENT".equals(scope)) {
			target = proc;
		}
		else if (target == null) {
			int i = PROCESS_ORDER.indexOf(proc);
			target = i > 0 ? PROCESS_ORDER.get(i - 1) : null;
		}
		else if (PROCESS_ORDER.indexOf(target) >= PROCESS_ORDER.indexOf(proc)) {
			throw ApiException.badRequest("INVALID_PROCESS_CODE", target + "는 " + proc + "보다 앞선 공정이 아닙니다.");
		}
		String t = target;
		RouteRun run = incident.getWaferIds().isEmpty() ? null
			: routeRepository.findByWaferIdOrderBySequenceNoAsc(incident.getWaferIds().getFirst()).stream()
				.filter(r -> r.getProcessCode().equals(t)).findFirst().orElse(null);
		Instant asOf = evidence.defaultAsOf();
		if (run == null) {
			return new Comparison(incidentId, scope, target, null, null, Map.of("available", false,
				"unavailableReason", "사건 웨이퍼의 해당 공정 run이 없습니다."), List.of(), dataVersion.current(), asOf);
		}
		return new Comparison(incidentId, scope, target, run.getRunId(), run.getEquipmentId(),
			evidence.runEvidence(run, asOf), evidence.featureEvidence(run, null), dataVersion.current(), asOf);
	}

	/** 기본 조회 범위: 사건 run 처리 시각(앞뒤 10분), 없으면 감지 시각 기준 −2h ~ +30m */
	private static Instant[] defaultSensorWindow(Incident incident, List<RouteRun> runs) {
		Instant start = runs.stream().map(RouteRun::getStartedAt).filter(Objects::nonNull).min(Instant::compareTo).orElse(null);
		Instant end = runs.stream().map(RouteRun::getEndedAt).filter(Objects::nonNull).max(Instant::compareTo).orElse(null);
		if (start == null || end == null) {
			return new Instant[] {incident.getDetectedAt().minus(Duration.ofHours(2)),
					incident.getDetectedAt().plus(Duration.ofMinutes(30))};
		}
		return new Instant[] {start.minus(Duration.ofMinutes(10)), end.plus(Duration.ofMinutes(10))};
	}

	public static final String NO_BASELINE_TRACE = "정상 기준 trace(BASELINE 원시 시계열)가 데이터 팩에 없습니다. "
			+ "run 평균 참고 분포(references)와 시계열 참조 범위(sensorEnvelopes)는 GET /api/monitoring/lots/{lotId}/summary에서 제공합니다.";

	/** run 안 시각(ms) → 단계: 준비(READY) · 본 처리(PROCESS) · 종료(END) — 프론트 명세의 recipeStep */
	public static String step(RouteRun r, long ms) {
		if (ms < r.getReadySeconds() * 1000L) {
			return "READY";
		}
		return ms < (r.getReadySeconds() + r.getProcessSeconds()) * 1000L ? "PROCESS" : "END";
	}

	/**
	 * 원시 시계열 → 샘플. 원본 간격(t0 + index × sourceIntervalMs)을 그대로 두고 보간하지 않는다. 결측(NaN)은 value=null · MISSING.
	 * sensorName은 팩에 이름 목록이 없어 sensorId를 그대로 쓰고(이름을 지어내지 않는다), chamberId는 장비 ID의 PM 부분이다.
	 */
	public static List<Sample> samples(RouteRun r, Trace t) {
		List<Sample> out = new ArrayList<>(t.values().length);
		String chamber = r.getEquipmentId() == null ? null : r.getEquipmentId().substring(r.getEquipmentId().lastIndexOf('-') + 1);
		for (int i = 0; i < t.values().length; i++) {
			long ms = i * t.intervalMs();
			double v = t.values()[i];
			out.add(new Sample(t.sensorId(), t.sensorId(), r.getRunId(), r.getWaferId(), r.getEquipmentId(), chamber, step(r, ms),
				t.t0().plusMillis(ms), Double.isNaN(v) ? null : v, t.unit(), Double.isNaN(v) ? "MISSING" : t.quality()));
		}
		return out;
	}

	/** 사건 웨이퍼가 사건 공정 이전에 실제로 거친 설비(PM) */
	public Set<String> upstreamEquipment(Incident incident) {
		if (incident.getWaferIds().isEmpty()) {
			return Set.of();
		}
		int seq = PROCESS_ORDER.indexOf(incident.getProcessCode()) + 1;
		return routeRepository.findByWaferIdInOrderByWaferIdAscSequenceNoAsc(incident.getWaferIds()).stream()
			.filter(r -> r.getSequenceNo() < seq).map(RouteRun::getEquipmentId)
			.collect(Collectors.toCollection(LinkedHashSet::new));
	}

	private static Instant parseTime(String name, String value, Instant fallback) {
		if (value == null || value.isBlank()) {
			return fallback;
		}
		try {
			return Instant.parse(value);
		}
		catch (DateTimeParseException e) {
			throw ApiException.badRequest("INVALID_PARAMETER", name + "는 ISO-8601 시각이어야 합니다 (예: 2026-10-20T01:00:00Z).");
		}
	}
}
