package com.fabcontext.api.domain.processdata;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.fabcontext.api.domain.processdata.PackStore.Trace;

/**
 * 시계열 지속 편차 구간 — 규칙 DEMO-SEGMENT-V2 (프론트 명세 「구간 검출과 화면 원칙」과 같은 계산). Agent 근거용이며 공개 API에는 내지 않는다.
 * <ol>
 * <li>READY · END와 PROCESS 처음 2초를 뺀다(t는 run 시작 t0부터 잰 초, 본 처리 끝은 포함하지 않는다)</li>
 * <li>끝점을 포함한 직전 smoothingSeconds(2초) 이동평균 — 창이 2초를 다 채운 뒤부터 계산한다</li>
 * <li>이동평균이 sensorEnvelope(lower · upper) 밖에서 같은 방향으로 minDurationSeconds(5초) 이상 이어지면 구간</li>
 * <li>결측 · 불량 취득은 구간을 끊고 이동평균 창도 비운다(가로질러 잇지 않는다). 구간 평균(mean)은 원본 값의 평균</li>
 * </ol>
 * 4σ · 2초 · 5초는 팀 시연 선택값이며 실제 공장 검증값이 아니다. 구간은 관측이지 고장 시점 · 원인 확정이 아니다.
 */
public final class SegmentDetector {

	public static final String RULE_VERSION = "DEMO-SEGMENT-V2";

	static final Set<String> GOOD = Set.of("GOOD", "GOOD_SYNTHETIC");

	static final double STABILIZE_SECONDS = 2.0;

	private SegmentDetector() {
	}

	public static List<Map<String, Object>> detect(RouteRun run, Trace trace, Map<String, Object> envelope) {
		List<Map<String, Object>> out = new ArrayList<>();
		if (envelope == null || !GOOD.contains(trace.quality())) {
			return out;
		}
		double lower = ((Number) envelope.get("lower")).doubleValue();
		double upper = ((Number) envelope.get("upper")).doubleValue();
		double win = ((Number) envelope.get("smoothingSeconds")).doubleValue();
		double minDur = ((Number) envelope.get("minDurationSeconds")).doubleValue();
		double start = run.getReadySeconds() + STABILIZE_SECONDS;
		double end = run.getReadySeconds() + run.getProcessSeconds();
		Deque<double[]> buf = new ArrayDeque<>(); // [t, v]
		double sum = 0;
		Segment cur = null;
		double[] values = trace.values();
		for (int i = 0; i < values.length; i++) {
			double t = i * trace.intervalMs() / 1000.0;
			if (t < start - 1e-9 || t >= end - 1e-9) {
				continue;
			}
			double v = values[i];
			if (Double.isNaN(v)) { // 결측 — 구간과 창을 끊는다
				cur = close(cur, out, minDur, trace, envelope);
				buf.clear();
				sum = 0;
				continue;
			}
			buf.addLast(new double[] {t, v});
			sum += v;
			while (buf.peekFirst()[0] < t - win - 1e-9) {
				sum -= buf.removeFirst()[1];
			}
			if (buf.peekLast()[0] - buf.peekFirst()[0] < win - 1e-9) {
				continue; // 창이 아직 2초를 채우지 않았다
			}
			double ma = sum / buf.size();
			String dir = ma < lower ? "LOW" : ma > upper ? "HIGH" : null;
			if (dir != null && cur != null && cur.direction.equals(dir)) {
				cur.to = t;
				cur.sum += v;
				cur.n++;
			}
			else {
				cur = close(cur, out, minDur, trace, envelope);
				if (dir != null) {
					cur = new Segment(dir, t, v);
				}
			}
		}
		close(cur, out, minDur, trace, envelope);
		return out;
	}

	private static Segment close(Segment s, List<Map<String, Object>> out, double minDur, Trace trace, Map<String, Object> env) {
		if (s != null && s.to - s.from >= minDur - 1e-9) {
			Map<String, Object> m = new LinkedHashMap<>();
			m.put("fromSeconds", round1(s.from));
			m.put("toSeconds", round1(s.to));
			m.put("confirmedAtSeconds", round1(s.from + minDur));
			m.put("direction", s.direction);
			m.put("mean", s.sum / s.n);
			m.put("unit", trace.unit());
			m.put("lower", env.get("lower"));
			m.put("upper", env.get("upper"));
			m.put("ruleVersion", env.getOrDefault("ruleVersion", RULE_VERSION));
			out.add(m);
		}
		return null;
	}

	private static double round1(double v) {
		return Math.round(v * 10) / 10.0;
	}

	private static final class Segment {

		final String direction;

		final double from;

		double to;

		double sum;

		int n;

		Segment(String direction, double t, double v) {
			this.direction = direction;
			this.from = t;
			this.to = t;
			this.sum = v;
			this.n = 1;
		}
	}
}
