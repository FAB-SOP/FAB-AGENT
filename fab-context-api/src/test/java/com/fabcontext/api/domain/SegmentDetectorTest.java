package com.fabcontext.api.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import com.fabcontext.api.domain.processdata.PackStore;
import com.fabcontext.api.domain.processdata.PackStore.Trace;
import com.fabcontext.api.domain.processdata.ProcessDataRepositories.RouteRunRepository;
import com.fabcontext.api.domain.processdata.RouteRun;
import com.fabcontext.api.domain.processdata.SegmentDetector;
import com.fabcontext.api.support.ApiTestSupport;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * 지속 편차 구간(DEMO-SEGMENT-V2) — 전달 묶음 sensor-segment-validation.json의 숫자와 같아야 한다:
 * CURRENT 600 시리즈 가운데 L006 head_pressure 101.1–210.9초 LOW · L007 slurry_flow 14–309초 LOW 둘뿐(정상 LOT 오탐 0)
 */
@DisplayName("센서 지속 편차 구간 (DEMO-SEGMENT-V2)")
class SegmentDetectorTest extends ApiTestSupport {

	@Autowired
	PackStore pack;

	@Autowired
	RouteRunRepository runs;

	@Test
	void onlyTheTwoScenarioSegmentsAcross600Series() {
		List<String> found = new ArrayList<>();
		int series = 0;
		for (RouteRun r : runs.findByPhase("CURRENT")) {
			for (Trace t : pack.traces(r.getRunId())) {
				series++;
				for (Map<String, Object> s : SegmentDetector.detect(r, t, pack.envelope(r.getProcessCode(), t.sensorId()).orElse(null))) {
					found.add(r.getRunId() + "/" + t.sensorId() + " " + s.get("fromSeconds") + "-" + s.get("toSeconds") + " " + s.get("direction"));
					if (t.sensorId().equals("head_pressure")) {
						assertThat((double) s.get("mean")).isCloseTo(2.5707481856233008, within(1e-9));
						assertThat(s.get("confirmedAtSeconds")).isEqualTo(106.1);
					}
					else {
						assertThat((double) s.get("mean")).isCloseTo(109.94891546959455, within(1e-9));
						assertThat(s.get("confirmedAtSeconds")).isEqualTo(19.0);
					}
				}
			}
		}
		assertThat(series).isEqualTo(600);
		assertThat(found).containsExactlyInAnyOrder("RUN-W-CURRENT-006-3-CMP/head_pressure 101.1-210.9 LOW",
			"RUN-W-CURRENT-007-3-CMP/slurry_flow 14.0-309.0 LOW");
	}

	@Test
	void internalTraceCarriesEnvelopeSegmentsAndEvidence() throws Exception {
		mvc.perform(get("/internal/runs/RUN-W-CURRENT-006-3-CMP/traces/head_pressure").header("Authorization", "Bearer dev-service-token"))
			.andExpect(jsonPath("$.data.data.sensorEnvelope.ruleVersion").value("DEMO-SEGMENT-V2"))
			.andExpect(jsonPath("$.data.data.segments[0].fromSeconds").value(101.1))
			.andExpect(jsonPath("$.data.data.segments[0].toSeconds").value(210.9))
			.andExpect(jsonPath("$.data.data.segments[0].direction").value("LOW"))
			.andExpect(jsonPath("$.data.evidenceRefs[1].evidenceId").value("E-SEG-RUN-W-CURRENT-006-3-CMP-head_pressure"));
		// L009 CMP 센서에는 구간을 만들지 않는다(원인은 앞 공정)
		mvc.perform(get("/internal/runs/RUN-W-CURRENT-009-3-CMP/traces/head_pressure").header("Authorization", "Bearer dev-service-token"))
			.andExpect(jsonPath("$.data.data.segments").isEmpty());
	}
}
