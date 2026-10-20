package com.fabcontext.api.domain.processdata;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Collectors;

import com.fabcontext.api.domain.processdata.ProcessDataRepositories.MetrologyRepository;
import com.fabcontext.api.domain.processdata.ProcessDataRepositories.RouteRunRepository;
import com.fabcontext.api.domain.processdata.ProcessDataRepositories.RunFeatureRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 공정 증거 계산 — 숫자는 여기서 계산하고 Agent · 화면은 해석만 한다 (팩 계약: "수치 계산을 검색 문서나 LLM 기억으로 대체하지 않는다").
 * <ul>
 * <li>run 증거: 5위치 두께 · 평균 · 증착/제거량 · 동결 관리한계(I, R1) 판정 · 제품 규격 · 정상(BASELINE) 분포 대비 편차</li>
 * <li>특징 증거: 센서 본 처리 평균의 참고 범위 이탈 · 설정값 대비 차이 · 같은 PM 정상 run 대비 편차</li>
 * </ul>
 * 분석 시점(asOf) 이후 공개된 계측과 FOLLOWUP run은 현재 판단에 쓰지 않는다.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ProcessEvidenceService {

	public static final List<String> PROCESS_ORDER = List.of("CVD", "ETCH", "CMP");

	public static final Map<String, String> STAGE_OF = Map.of("CVD", "AFTER_CVD", "ETCH", "AFTER_ETCH", "CMP", "AFTER_CMP");

	/** 정상 분포 대비 이 배수(σ) 이상 벗어나면 "정상 변동 밖"으로 표시한다 — 시연용 표시 기준이며 등록 규칙(R1)이 아니다 */
	public static final double SIGMA_MARK = 2.5;

	private final RouteRunRepository runRepository;
	private final MetrologyRepository metrologyRepository;
	private final RunFeatureRepository featureRepository;
	private final PackStore pack;

	private volatile Map<String, double[]> amountStats;

	private volatile Map<String, double[]> featureStats;

	public Instant defaultAsOf() {
		return pack.analysisAsOf();
	}

	/** run 하나의 공정 증거 — 계측이 asOf 이후에 공개됐으면 available=false */
	public Map<String, Object> runEvidence(RouteRun r, Instant asOf) {
		Map<String, Object> m = new LinkedHashMap<>();
		m.put("runId", r.getRunId());
		m.put("waferId", r.getWaferId());
		m.put("lotId", r.getLotId());
		m.put("phase", r.getPhase());
		m.put("processCode", r.getProcessCode());
		m.put("equipmentId", r.getEquipmentId());
		m.put("recipeId", r.getRecipeId());
		m.put("startedAt", kst(r.getStartedAt()));
		m.put("endedAt", kst(r.getEndedAt()));
		m.put("processSeconds", r.getProcessSeconds());
		m.put("controlMode", r.getControlMode());
		m.put("upstreamRunId", r.getUpstreamRunId());
		m.put("metrologyAvailableAt", kst(r.getMetrologyAvailableAt()));
		boolean available = asOf == null || !r.getMetrologyAvailableAt().isAfter(asOf);
		m.put("available", available);
		if (!available) {
			m.put("unavailableReason", "계측 공개 시각(" + r.getMetrologyAvailableAt() + ")이 분석 시점(" + asOf + ") 이후입니다.");
			return m;
		}
		String proc = r.getProcessCode();
		List<Metrology> sites = metrologyRepository.findByRunIdOrderBySiteIdAsc(r.getRunId());
		m.put("sites", sites.stream().map(s -> Map.<String, Object>of("siteId", s.getSiteId(), "thicknessA", round(s.getValue(), 1)))
			.toList());
		var stats = sites.stream().mapToDouble(Metrology::getValue).summaryStatistics();
		m.put("siteRangeA", sites.isEmpty() ? null : round(stats.getMax() - stats.getMin(), 1));
		m.put("inputMeanA", round(r.getInputMeanA(), 1));
		m.put("outputMeanA", round(r.getOutputMeanA(), 1));
		m.put("amountKind", "CVD".equals(proc) ? "DEPOSITED" : "REMOVED");
		m.put("amountA", round(r.getAddedOrRemovedA(), 1));
		pack.limit(proc).ifPresent(l -> {
			Map<String, Object> lim = new LinkedHashMap<>();
			for (String k : List.of("limitId", "metric", "chartType", "rule", "center", "lcl", "ucl", "lsl", "usl", "specOrigin")) {
				lim.put(k, l.get(k) instanceof Number n ? round(n.doubleValue(), 1) : l.get(k));
			}
			m.put("limit", lim);
			double out = r.getOutputMeanA();
			String side = out > num(l.get("ucl")) ? "ABOVE_UCL" : out < num(l.get("lcl")) ? "BELOW_LCL" : null;
			m.put("r1", Map.of("outside", side != null, "side", Objects.requireNonNullElse(side, "WITHIN")));
			m.put("oos", l.get("usl") != null && (out > num(l.get("usl")) || out < num(l.get("lsl"))));
		});
		double[] pm = amountStats().get(proc + "|" + r.getEquipmentId());
		double[] all = amountStats().get(proc + "|ALL");
		Map<String, Object> base = new LinkedHashMap<>();
		base.put("samePm", statView(pm));
		base.put("allPm", statView(all));
		m.put("baselineAmount", base);
		m.put("amountZSamePm", z(r.getAddedOrRemovedA(), pm));
		m.put("amountZAllPm", z(r.getAddedOrRemovedA(), all));
		m.put("amountDeltaFromBaselineA", all == null ? null : round(r.getAddedOrRemovedA() - all[0], 1));
		Double zz = z(r.getAddedOrRemovedA(), pm);
		m.put("amountWithinNormalVariation", zz == null || Math.abs(zz) < SIGMA_MARK);
		m.put("normalVariationSigma", SIGMA_MARK); // 위 판정의 기준 |z| — 요청 브리프가 숫자를 그대로 옮긴다
		return m;
	}

	/** run의 센서 특징 — 참고 범위(run 평균의 분포, 점 단위 SPC 아님) · 설정값 차이 · 같은 PM 정상 run 대비 편차 */
	public List<Map<String, Object>> featureEvidence(RouteRun r, List<String> sensorIds) {
		List<Map<String, Object>> out = new ArrayList<>();
		for (RunFeature f : featureRepository.findByRunIdOrderBySensorIdAsc(r.getRunId())) {
			if (sensorIds != null && !sensorIds.contains(f.getSensorId())) {
				continue;
			}
			Map<String, Object> m = new LinkedHashMap<>();
			m.put("sensorId", f.getSensorId());
			m.put("unit", f.getUnit());
			m.put("mean", round(f.getMean(), 4));
			m.put("std", round(f.getStd(), 4));
			m.put("setpoint", f.getSetpoint());
			m.put("deviationFromSetpointPct", f.getSetpoint() == null || f.getSetpoint() == 0 ? null
					: round((f.getMean() - f.getSetpoint()) / f.getSetpoint() * 100, 2));
			pack.band(f.getProcessCode(), f.getSensorId()).ifPresent(b -> {
				m.put("referenceBand", Map.of("lcl", round(num(b.get("lcl")), 4), "ucl", round(num(b.get("ucl")), 4)));
				m.put("outsideReferenceBand", f.getMean() < num(b.get("lcl")) || f.getMean() > num(b.get("ucl")));
			});
			double[] pm = featureStats().get(f.getProcessCode() + "|" + f.getEquipmentId() + "|" + f.getSensorId());
			m.put("baselineSamePm", statView(pm));
			m.put("zSamePm", z(f.getMean(), pm));
			out.add(m);
		}
		return out;
	}

	/** 웨이퍼의 실제 공정 경로와 단계별 증거 — PM 이름이 같다고 앞뒤 장비를 연결하지 않는다 */
	public Map<String, Object> waferEvidence(String waferId, Instant asOf) {
		List<RouteRun> runs = runRepository.findByWaferIdOrderBySequenceNoAsc(waferId);
		Map<String, Object> m = new LinkedHashMap<>();
		m.put("waferId", waferId);
		m.put("lotId", runs.isEmpty() ? null : runs.getFirst().getLotId());
		m.put("path", runs.stream().map(r -> r.getProcessCode() + ":" + r.getEquipmentId()).collect(Collectors.joining(" → ")));
		m.put("steps", runs.stream().map(r -> runEvidence(r, asOf)).toList());
		return m;
	}

	private Map<String, double[]> amountStats() {
		if (amountStats == null) {
			amountStats = stats(runRepository.findByPhase("BASELINE"), RouteRun::getAddedOrRemovedA,
				r -> List.of(r.getProcessCode() + "|" + r.getEquipmentId(), r.getProcessCode() + "|ALL"));
		}
		return amountStats;
	}

	private Map<String, double[]> featureStats() {
		if (featureStats == null) {
			featureStats = stats(featureRepository.findByPhase("BASELINE"), RunFeature::getMean,
				f -> List.of(f.getProcessCode() + "|" + f.getEquipmentId() + "|" + f.getSensorId()));
		}
		return featureStats;
	}

	/** key → [mean, sd, n] */
	private static <T> Map<String, double[]> stats(List<T> rows, java.util.function.ToDoubleFunction<T> value,
			Function<T, List<String>> keys) {
		Map<String, List<Double>> by = new HashMap<>();
		for (T row : rows) {
			for (String k : keys.apply(row)) {
				by.computeIfAbsent(k, x -> new ArrayList<>()).add(value.applyAsDouble(row));
			}
		}
		Map<String, double[]> out = new HashMap<>();
		by.forEach((k, vs) -> {
			double mean = vs.stream().mapToDouble(Double::doubleValue).average().orElse(0);
			double var = vs.size() < 2 ? 0 : vs.stream().mapToDouble(v -> (v - mean) * (v - mean)).sum() / (vs.size() - 1);
			out.put(k, new double[] {mean, Math.sqrt(var), vs.size()});
		});
		return out;
	}

	private static Map<String, Object> statView(double[] s) {
		if (s == null) {
			return null;
		}
		return Map.of("mean", round(s[0], 3), "sd", round(s[1], 3), "n", (int) s[2]);
	}

	private static Double z(double v, double[] s) {
		return s == null || s[1] == 0 ? null : round((v - s[0]) / s[1], 2);
	}

	/** 시각은 KST ISO-8601 문자열로 낸다 (원본 팩과 같은 표기) */
	public static String kst(Instant t) {
		return t == null ? null : t.atZone(java.time.ZoneId.of("Asia/Seoul")).toOffsetDateTime().toString();
	}

	static double num(Object o) {
		return ((Number) o).doubleValue();
	}

	public static double round(double v, int digits) {
		double f = Math.pow(10, digits);
		return Math.round(v * f) / f;
	}

	static Comparator<RouteRun> bySequence() {
		return Comparator.comparingInt(RouteRun::getSequenceNo);
	}
}
