package com.fabcontext.api.domain.monitoring;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.fabcontext.api.auth.AccessPolicy;
import com.fabcontext.api.auth.AuthUser;
import com.fabcontext.api.common.ApiException;
import com.fabcontext.api.domain.incident.IncidentRepository;
import com.fabcontext.api.domain.processdata.DataVersion;
import com.fabcontext.api.domain.processdata.PackStore;
import com.fabcontext.api.domain.processdata.PackStore.Trace;
import com.fabcontext.api.domain.processdata.ProcessDataDtos.Sample;
import com.fabcontext.api.domain.processdata.ProcessDataRepositories.RouteRunRepository;
import com.fabcontext.api.domain.processdata.ProcessDataService;
import com.fabcontext.api.domain.processdata.RouteRun;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 모니터링 GET 3개(프론트 계약 combined.openapi.json의 모니터링 경로) — 사건이 없는 정상 LOT · PM도 조회한다.
 * <ul>
 * <li>run · 5위치 계측 · 특징 · 관리한계 · 센서 정의 · 참고 분포 · 시계열 참조 범위는 데이터 팩 JSON을 <b>그대로</b> 내린다
 * (원본 필드를 지우거나 바꾸지 않는다 — 명세 「원래 source JSON의 추가 필드는 허용」). incidentId만 DB 사건에서 채운다</li>
 * <li>대상은 현재(CURRENT) LOT뿐이다. BASELINE은 원시 시계열이 없고 FOLLOWUP은 분석 시점 이후 자료다</li>
 * <li>references(run 평균 참고 분포)와 sensorEnvelopes(시계열 구간용 참조 범위)는 다른 기준이라 섞지 않는다</li>
 * </ul>
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class MonitoringService {

	static final Set<String> PROCESSES = Set.of("CVD", "ETCH", "CMP");

	private final PackStore pack;
	private final DataVersion dataVersion;
	private final AccessPolicy accessPolicy;
	private final IncidentRepository incidentRepository;
	private final RouteRunRepository routeRepository;

	private volatile Index index;

	/** 팩의 현재 LOT 행 묶음 — 처음 조회 때 한 번 읽는다(팩은 실행 중에 바뀌지 않는다) */
	private record Index(List<Map<String, Object>> runs, Map<String, List<Map<String, Object>>> sitesByRun,
			Map<String, List<Map<String, Object>>> featuresByRun) {
	}

	public Map<String, Object> lots(AuthUser user, String processCode) {
		requireProcess(user, processCode);
		List<String> lots = index().runs().stream().filter(r -> processCode.equals(r.get("process")))
			.map(r -> String.valueOf(r.get("lotId"))).distinct().sorted().toList();
		return Map.of("items", lots);
	}

	public Map<String, Object> summary(AuthUser user, String lotId, String processCode) {
		requireProcess(user, processCode);
		List<Map<String, Object>> runs = index().runs().stream()
			.filter(r -> lotId.equals(r.get("lotId")) && processCode.equals(r.get("process")))
			.sorted(Comparator.comparing(r -> String.valueOf(r.get("equipmentId")))).toList();
		if (runs.isEmpty()) {
			throw ApiException.notFound("lot", lotId);
		}
		Map<String, String> incidentOfRun = incidentOfRun(runs.stream().map(r -> String.valueOf(r.get("runId"))).toList());
		List<Map<String, Object>> out = new ArrayList<>();
		for (Map<String, Object> r : runs) {
			Map<String, Object> m = new LinkedHashMap<>(r);
			String runId = String.valueOf(r.get("runId"));
			m.put("incidentId", incidentOfRun.get(runId));
			m.put("sites", index().sitesByRun().getOrDefault(runId, List.of()));
			m.put("features", index().featuresByRun().getOrDefault(runId, List.of()));
			out.add(m);
		}
		Map<String, Object> data = new LinkedHashMap<>();
		data.put("lotId", lotId);
		data.put("processCode", processCode);
		data.put("runs", out);
		data.put("limits", pack.limit(processCode).orElse(null));
		data.put("sensorDefinitions", byProcess(pack.sensors(), processCode));
		data.put("references", byProcess(pack.bands(), processCode));
		data.put("sensorEnvelopes", byProcess(pack.envelopes(), processCode));
		data.put("dataVersion", dataVersion.current());
		return data;
	}

	/** 공정의 센서 정의 — 로트 요약의 sensorDefinitions와 같은 목록(보고서 원인 분류의 장비 파라미터 선택지) */
	public Map<String, Object> sensorDefinitions(AuthUser user, String processCode) {
		requireProcess(user, processCode);
		Map<String, Object> data = new LinkedHashMap<>();
		data.put("processCode", processCode);
		data.put("items", byProcess(pack.sensors(), processCode));
		data.put("dataVersion", dataVersion.current());
		return data;
	}

	public Map<String, Object> sensorData(AuthUser user, String runId, String sensorId, Integer limitParam) {
		if (sensorId == null || sensorId.isBlank()) {
			throw ApiException.unprocessable("INVALID_SENSOR_ID", "sensorId가 필요합니다.");
		}
		int limit = limitParam == null ? ProcessDataService.DEFAULT_LIMIT : limitParam;
		if (limit < 1 || limit > ProcessDataService.MAX_LIMIT) {
			throw ApiException.unprocessable("INVALID_LIMIT", "limit은 1~" + ProcessDataService.MAX_LIMIT + " 사이여야 합니다.");
		}
		Map<String, Object> source = index().runs().stream().filter(r -> runId.equals(r.get("runId"))).findFirst()
			.orElseThrow(() -> ApiException.notFound("run", runId));
		accessPolicy.requireScope(user, String.valueOf(source.get("process")));
		RouteRun run = routeRepository.findByRunId(runId).orElseThrow(() -> ApiException.notFound("run", runId));
		Trace trace = pack.trace(runId, sensorId).orElseThrow(() -> ApiException.notFound("sensor", runId + "/" + sensorId));
		List<Sample> samples = ProcessDataService.samples(run, trace);
		Map<String, Object> data = new LinkedHashMap<>();
		data.put("samples", samples.size() > limit ? samples.subList(0, limit) : samples);
		data.put("totalCount", samples.size());
		data.put("truncated", samples.size() > limit);
		data.put("baseline", null); // 정상 기준 trace가 없다 — 참조값은 상세 응답의 references · sensorEnvelopes
		data.put("dataVersion", dataVersion.current());
		return data;
	}

	private void requireProcess(AuthUser user, String processCode) {
		if (processCode == null || !PROCESSES.contains(processCode)) {
			throw ApiException.unprocessable("INVALID_PROCESS_CODE", "processCode는 CVD · ETCH · CMP 중 하나여야 합니다.");
		}
		accessPolicy.requireScope(user, processCode);
	}

	/** run ID → 그 run을 담은 사건(먼저 검출된 것) */
	private Map<String, String> incidentOfRun(List<String> runIds) {
		Map<String, String> m = new HashMap<>();
		for (IncidentRepository.RunIncident r : incidentRepository.findByRunIds(runIds)) {
			m.putIfAbsent(r.getRunId(), r.getIncidentId());
		}
		return m;
	}

	private static List<Map<String, Object>> byProcess(List<Map<String, Object>> rows, String processCode) {
		return rows.stream().filter(r -> processCode.equals(r.get("process"))).toList();
	}

	private Index index() {
		if (index == null) {
			synchronized (this) {
				if (index == null) {
					List<Map<String, Object>> runs = pack.list("data/process-runs.json").stream()
						.filter(r -> "CURRENT".equals(r.get("phase"))).toList();
					Map<String, List<Map<String, Object>>> sites = new HashMap<>();
					pack.list("data/site-metrology.json").stream().filter(r -> "CURRENT".equals(r.get("phase")))
						.forEach(r -> sites.computeIfAbsent(String.valueOf(r.get("runId")), k -> new ArrayList<>()).add(r));
					Map<String, List<Map<String, Object>>> features = new HashMap<>();
					pack.list("data/run-sensor-features.json").stream().filter(r -> "CURRENT".equals(r.get("phase")))
						.forEach(r -> features.computeIfAbsent(String.valueOf(r.get("runId")), k -> new ArrayList<>()).add(r));
					index = new Index(runs, sites, features);
				}
			}
		}
		return index;
	}
}
