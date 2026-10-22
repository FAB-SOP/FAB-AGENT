package com.fabcontext.api.domain;

import java.time.Instant;
import java.util.*;
import java.util.zip.GZIPInputStream;
import com.fabcontext.api.common.Json;
import com.fabcontext.api.domain.processdata.*;
import com.fabcontext.api.domain.processdata.PackStore.Trace;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.core.type.TypeReference;
import static org.assertj.core.api.Assertions.*;

class PriorityEvidenceCalculatorTest {
	private final PriorityEvidencePolicy policy=new PriorityEvidencePolicy();
	private final Instant start=Instant.parse("2026-10-19T00:00:00Z");
	private RouteRun run() { return RouteRun.builder().runId("run").startedAt(start).readySeconds(0).processSeconds(30).build(); }
	private Trace trace(double[] values,long interval,String quality) { return new Trace("run","sensor","psi",start,interval,List.of(),values,quality); }
	private Map<String,Object> envelope() { return Map.of("lower",9.0,"upper",11.0); }
	private double[] values() { double[] values=new double[300]; Arrays.fill(values,10); return values; }
	private PriorityEvidenceCalculator.Observation observe(double[] values) { return PriorityEvidenceCalculator.observe(run(),trace(values,100,"GOOD_SYNTHETIC"),envelope(),start.plusSeconds(30),policy); }

	@Test void subsecondSpikeIsNotAValidDeviation() {
		double[] values=values(); values[100]=12;
		assertThat(observe(values).segments()).isEmpty();
	}
	@Test void sustainedDeviationRecoversOnlyAfterAnInRangeSmoothedSample() {
		double[] values=values(); Arrays.fill(values,100,180,12);
		var segment=observe(values).segments().getFirst();
		assertThat(segment.durationSeconds()).isGreaterThanOrEqualTo(5);
		assertThat(segment.recovered()).isTrue();
	}
	@Test void endOfTraceIsNotRecovery() {
		double[] values=values(); Arrays.fill(values,100,300,12);
		assertThat(observe(values).segments().getFirst().recovered()).isFalse();
	}
	@Test void missingSampleSplitsTheWindowAndPreventsInventedRecovery() {
		double[] values=values(); Arrays.fill(values,100,180,12); values[140]=Double.NaN;
		var observation=observe(values);
		assertThat(observation.status()).isEqualTo("INSUFFICIENT");
		assertThat(observation.missingData()).contains("SAMPLES_MISSING");
		assertThat(observation.segments()).hasSize(2);
		assertThat(observation.segments().getFirst().recovered()).isFalse();
	}
	@Test void futureSamplesAreNotUsed() {
		double[] values=values(); Arrays.fill(values,150,200,12);
		var observed=PriorityEvidenceCalculator.observe(run(),trace(values,100,"GOOD"),envelope(),start.plusSeconds(10),policy);
		assertThat(observed.segments()).isEmpty(); assertThat(observed.missingData()).contains("RUN_IN_PROGRESS");
	}
	@Test void readyAndShutdownAreExcluded() {
		double[] values=values(); Arrays.fill(values,0,15,12); Arrays.fill(values,290,300,12);
		assertThat(observe(values).segments()).isEmpty();
	}
	@ParameterizedTest @ValueSource(longs={0,-1,300,1100,2000})
	void unsupportedCadenceIsInsufficient(long cadence) {
		var observed=PriorityEvidenceCalculator.observe(run(),trace(values(),cadence,"GOOD"),envelope(),start.plusSeconds(30),policy);
		assertThat(observed.missingData()).contains("CADENCE_UNSUPPORTED");
	}
	@Test void missingEnvelopeAndBadQualityAreNotNormal() {
		assertThat(PriorityEvidenceCalculator.observe(run(),trace(values(),100,"BAD"),envelope(),start.plusSeconds(30),policy).status()).isEqualTo("INSUFFICIENT");
		assertThat(PriorityEvidenceCalculator.observe(run(),trace(values(),100,"GOOD"),null,start.plusSeconds(30),policy).status()).isEqualTo("INSUFFICIENT");
	}
	@Test void overlappingWindowsUseIntersectionRatherThanSensorCount() {
		var a=new PriorityEvidenceCalculator.Segment(10,13,3,"LOW",true,11);
		var b=new PriorityEvidenceCalculator.Segment(12,15,3,"LOW",true,13);
		assertThat(PriorityEvidenceCalculator.maximumOverlap(List.of(a),List.of(b))).isEqualTo(1);
		assertThat(PriorityEvidenceCalculator.maximumOverlap(List.of(a),List.of(new PriorityEvidenceCalculator.Segment(15,18,3,"LOW",true,16)))).isZero();
	}
	@Test void originalPmAddonTracesReproduceTheProvidedDurations() throws Exception {
		// PM ZIP의 대상 원시 시계열 3개만 보존한 테스트 전용 자료. 정답 파일·운영 데이터 적재는 하지 않는다.
		Map<String,Object> fixture;
		try(var input=new GZIPInputStream(getClass().getResourceAsStream("/priority/addon-traces.json.gz"))) {
			fixture=Json.read(new String(input.readAllBytes(),java.nio.charset.StandardCharsets.UTF_8),new TypeReference<>() {});
		}
		@SuppressWarnings("unchecked") var traces=(List<Map<String,Object>>)fixture.get("traces");
		List<PriorityEvidenceCalculator.Segment> rotation=new ArrayList<>();
		for(var raw:traces) {
			Instant t0=Instant.parse((String)raw.get("t0"));
			double[] data=((List<?>)raw.get("values")).stream().mapToDouble(n->((Number)n).doubleValue()).toArray();
			String sensor=(String)raw.get("sensorId");
			Trace trace=new Trace((String)raw.get("runId"),sensor,(String)raw.get("unit"),t0,((Number)raw.get("sourceIntervalMs")).longValue(),List.of(),data,(String)raw.get("quality"));
			RouteRun run=RouteRun.builder().startedAt(t0).readySeconds(10).processSeconds(300).build();
			Map<String,Object> bounds=switch(sensor) {
				case "head_pressure" -> Map.of("lower",2.973761813108883,"upper",3.025702793557784);
				case "platen_speed" -> Map.of("lower",89.59778375729691,"upper",90.3863351227031);
				default -> Map.of("lower",86.63763582556398,"upper",87.36500306776936);
			};
			var observed=PriorityEvidenceCalculator.observe(run,trace,bounds,t0.plusSeconds(320),policy);
			assertThat(observed.status()).isEqualTo("COMPLETE");
			assertThat(observed.segments()).hasSize(1);
			var segment=observed.segments().getFirst();
			assertThat(segment.durationSeconds()).isEqualTo(sensor.equals("head_pressure")?2.7:3.0);
			assertThat(segment.recovered()).isTrue();
			if(!sensor.equals("head_pressure")) rotation.add(segment);
		}
		assertThat(PriorityEvidenceCalculator.maximumOverlap(List.of(rotation.get(0)),List.of(rotation.get(1)))).isEqualTo(3);
	}
}
