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
		double minDuration = ((Number) envelope.get("minDurationSeconds")).doubleValue();
		MovingWindow window = new MovingWindow(((Number) envelope.get("smoothingSeconds")).doubleValue());
		double start = run.getReadySeconds() + STABILIZE_SECONDS;
		double end = run.getReadySeconds() + run.getProcessSeconds();
		Segment current = null;
		double[] values = trace.values();
		for (int i = 0; i < values.length; i++) {
			double seconds = i * trace.intervalMs() / 1000.0;
			if (seconds < start - 1e-9 || seconds >= end - 1e-9) {
				continue;
			}
			double value = values[i];
			if (Double.isNaN(value)) { // 결측 — 구간과 창을 끊는다
				current = close(current, out, minDuration, trace, envelope);
				window.clear();
				continue;
			}
			window.add(seconds, value);
			if (!window.isFull()) {
				continue; // 창이 아직 2초를 채우지 않았다
			}
			double movingAverage = window.mean();
			String direction = movingAverage < lower ? "LOW" : movingAverage > upper ? "HIGH" : null;
			current = track(current, direction, seconds, value, out, minDuration, trace, envelope);
		}
		close(current, out, minDuration, trace, envelope);
		return out;
	}

	/** 같은 방향이 이어지면 구간을 늘리고, 아니면 지금 구간을 닫고 (밖이면) 새 구간을 연다 */
	private static Segment track(Segment current, String direction, double seconds, double value,
			List<Map<String, Object>> out, double minDuration, Trace trace, Map<String, Object> envelope) {
		if (direction != null && current != null && current.direction.equals(direction)) {
			current.to = seconds;
			current.sum += value;
			current.n++;
			return current;
		}
		close(current, out, minDuration, trace, envelope);
		return direction != null ? new Segment(direction, seconds, value) : null;
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

	/** 끝점을 포함한 직전 width초 이동평균 창 — 합계를 더하고 빼는 순서는 예전 계산 그대로 */
	private static final class MovingWindow {

		private final double width;

		private final Deque<double[]> samples = new ArrayDeque<>(); // [t, v]

		private double sum;

		MovingWindow(double width) {
			this.width = width;
		}

		void add(double seconds, double value) {
			samples.addLast(new double[] {seconds, value});
			sum += value;
			while (samples.peekFirst()[0] < seconds - width - 1e-9) {
				sum -= samples.removeFirst()[1];
			}
		}

		boolean isFull() {
			return samples.peekLast()[0] - samples.peekFirst()[0] >= width - 1e-9;
		}

		double mean() {
			return sum / samples.size();
		}

		void clear() {
			samples.clear();
			sum = 0;
		}
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
