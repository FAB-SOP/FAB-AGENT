package com.fabcontext.api.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fabcontext.api.support.ApiTestSupport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("P04~P06 센서 · 계측 · 경로 · 변경 · 비교")
class ProcessDataApiTest extends ApiTestSupport {

	Session etch;
	String ev1006;
	String ev1009;

	@BeforeEach
	void setUp() throws Exception {
		etch = cmp();
		ev1006 = seededIncident(etch, "EV-1006");
		ev1009 = seededIncident(etch, "EV-1009");
	}

	@Test
	void sensorDataDefaultsToIncidentRunWindow() throws Exception {
		// 팩 원시 시계열: L006 CMP-PM3 run의 CMP 센서 5개 (0.1초 둘 · 1초 셋) = 7,365점
		getAs(etch, "/api/incidents/{id}/sensor-data", ev1006)
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.totalCount").value(7365))
			.andExpect(jsonPath("$.data.truncated").value(true))
			.andExpect(jsonPath("$.data.samples[*].runId", everyItem(is("RUN-W-CURRENT-006-3-CMP"))))
			.andExpect(jsonPath("$.data.qualitySummary.GOOD_SYNTHETIC").value(7365))
			.andExpect(jsonPath("$.data.baseline").doesNotExist())
			.andExpect(jsonPath("$.data.rawRef").value(startsWith("raw://sensor-traces/")))
			.andExpect(jsonPath("$.data.dataVersion").value("causal-pack-v2-scenarios"));
	}

	@Test
	void sensorFiltersLimitAndBaseline() throws Exception {
		getAs(etch, "/api/incidents/{id}/sensor-data?sensorId=head_pressure&recipeStep=PROCESS&limit=5000", ev1006)
			.andExpect(jsonPath("$.data.totalCount").value(3000))
			.andExpect(jsonPath("$.data.samples[*].sensorId", everyItem(is("head_pressure"))))
			.andExpect(jsonPath("$.data.samples[*].recipeStep", everyItem(is("PROCESS"))))
			.andExpect(jsonPath("$.data.samples[0].unit").value("psi"));
		getAs(etch, "/api/incidents/{id}/sensor-data?limit=5", ev1006)
			.andExpect(jsonPath("$.data.samples", hasSize(5))).andExpect(jsonPath("$.data.truncated").value(true));
		// 프론트 계약 Baseline은 정상 기준 trace다 — 팩에 기준 trace가 없어 null과 이유를 준다(참고 분포 · 시계열 참조 범위는 모니터링 상세)
		getAs(etch, "/api/incidents/{id}/sensor-data?includeBaseline=true&sensorId=head_pressure&limit=1", ev1006)
			.andExpect(jsonPath("$.data.baseline").value(nullValue()))
			.andExpect(jsonPath("$.data.baselineUnavailableReason").value(containsString("/api/monitoring/lots/{lotId}/summary")));
		// 기본 limit 5000 — L006 head_pressure 3201점이 한 번에 온다(프론트는 truncated=true면 구간 판정을 생략한다)
		getAs(etch, "/api/incidents/{id}/sensor-data?sensorId=head_pressure", ev1006)
			.andExpect(jsonPath("$.data.truncated").value(false)).andExpect(jsonPath("$.data.query.limit").value(5000))
			.andExpect(jsonPath("$.data.samples[*].recipeStep", hasItems("READY", "PROCESS", "END")))
			.andExpect(jsonPath("$.data.samples[0].chamberId").value("PM3"));
	}

	@Test
	void sensorParameterValidation() throws Exception {
		getAs(etch, "/api/incidents/{id}/sensor-data?from=yesterday", ev1006).andExpect(status().isBadRequest());
		getAs(etch, "/api/incidents/{id}/sensor-data?from=2026-01-02T00:00:00Z&to=2026-01-01T00:00:00Z", ev1006)
			.andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.error.code").value("INVALID_TIME_RANGE"));
		getAs(etch, "/api/incidents/{id}/sensor-data?limit=5001", ev1006).andExpect(status().isBadRequest());
		getAs(etch, "/api/incidents/{id}/sensor-data?from=2000-01-01T00:00:00Z&to=2000-01-02T00:00:00Z", ev1006)
			.andExpect(status().isOk()).andExpect(jsonPath("$.data.totalCount").value(0));
	}

	@Test
	void metrologyByStageFeatureAndSite() throws Exception {
		// 한 장의 5위치 × (CVD 후 · Etch 후 · CMP 후)
		getAs(etch, "/api/incidents/{id}/metrology", ev1006)
			.andExpect(jsonPath("$.data.totalCount").value(15))
			.andExpect(jsonPath("$.data.specNote").value(containsString("5위치")));
		getAs(etch, "/api/incidents/{id}/metrology?stage=AFTER_CMP", ev1006)
			.andExpect(jsonPath("$.data.items[*].siteId", containsInAnyOrder("CENTER", "NORTH", "EAST", "SOUTH", "WEST")))
			.andExpect(jsonPath("$.data.items[*].unit", everyItem(is("Å"))))
			.andExpect(jsonPath("$.data.items[*].specId", everyItem(is("QL-CMP-V1"))))
			.andExpect(jsonPath("$.data.items[*].value", everyItem(greaterThan(10133.5))));
		getAs(etch, "/api/incidents/{id}/metrology?stage=AFTER_CMP&siteId=CENTER", ev1006)
			.andExpect(jsonPath("$.data.items", hasSize(1)));
		getAs(etch, "/api/incidents/{id}/metrology?featureId=OTHER", ev1006).andExpect(jsonPath("$.data.items", hasSize(0)));
		getAs(etch, "/api/incidents/{id}/metrology?stage=AFTER_DEVELOP", ev1006).andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.error.code").value("INVALID_STAGE"));
	}

	@Test
	void waferRouteOrderAndAccess() throws Exception {
		getAs(etch, "/api/wafers/W-CURRENT-006-3/route")
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.lotId").value("CURRENT-L006"))
			.andExpect(jsonPath("$.data.runs[*].sequenceNo", contains(1, 2, 3)))
			.andExpect(jsonPath("$.data.runs[*].processCode", contains("CVD", "ETCH", "CMP")))
			// PM 이름이 같아도 앞뒤를 잇지 않는다 — 실제 경로는 PM1 → PM2 → PM3
			.andExpect(jsonPath("$.data.runs[*].equipmentId", contains("CVD-PM1", "ETCH-PM2", "CMP-PM3")))
			.andExpect(jsonPath("$.data.runs[2].recipeId").value("DEMO-SIO2-CMP-V1"));
		getAs(cvd(), "/api/wafers/W-CURRENT-006-3/route").andExpect(status().isOk());
		getAs(etch, "/api/wafers/W-CURRENT-006-3/route?layerId=M2").andExpect(jsonPath("$.data.runs", hasSize(0)));
		getAs(etch, "/api/wafers/W-NOPE/route").andExpect(status().isNotFound());
	}

	@Test
	void changesSeparateCurrentAndUpstreamAndKeepRecordedTime() throws Exception {
		// EV-1009 웨이퍼는 ETCH-PM2를 지났다 — 그 PM의 정비 기록이 앞 공정(UPSTREAM)으로 보인다
		getAs(etch, "/api/incidents/{id}/changes", ev1009)
			.andExpect(jsonPath("$.data.items[*].changeId", contains("DEMO-CHANGE-E02")))
			.andExpect(jsonPath("$.data.items[0].relation").value("UPSTREAM"))
			.andExpect(jsonPath("$.data.items[0].recordedAt").exists());
		getAs(etch, "/api/incidents/{id}/changes?type=recipe", ev1009).andExpect(jsonPath("$.data.items", hasSize(0)));
		getAs(etch, "/api/incidents/{id}/changes?type=chamber_service", ev1009).andExpect(jsonPath("$.data.items", hasSize(1)));
		getAs(etch, "/api/incidents/{id}/changes?from=bad", ev1009).andExpect(status().isBadRequest());
	}

	@Test
	void comparisonsShowStepEvidence() throws Exception {
		// L009: CMP 후 UCL 초과(규격 안) · CMP 제거량은 정상 변동 안 · 앞 공정 Etch 제거량은 정상 변동 밖
		getAs(etch, "/api/incidents/{id}/comparisons", ev1009)
			.andExpect(jsonPath("$.data.scope").value("CURRENT"))
			.andExpect(jsonPath("$.data.processCode").value("CMP"))
			.andExpect(jsonPath("$.data.equipmentId").value("CMP-PM3"))
			.andExpect(jsonPath("$.data.evidence.r1.side").value("ABOVE_UCL"))
			.andExpect(jsonPath("$.data.evidence.oos").value(false))
			.andExpect(jsonPath("$.data.evidence.amountWithinNormalVariation").value(true))
			.andExpect(jsonPath("$.data.evidence.sites", hasSize(5)))
			.andExpect(jsonPath("$.data.features", hasSize(5)));
		getAs(etch, "/api/incidents/{id}/comparisons?scope=upstream", ev1009)
			.andExpect(jsonPath("$.data.processCode").value("ETCH"))
			.andExpect(jsonPath("$.data.equipmentId").value("ETCH-PM2"))
			.andExpect(jsonPath("$.data.evidence.amountWithinNormalVariation").value(false))
			.andExpect(jsonPath("$.data.evidence.r1.outside").value(false));
		getAs(etch, "/api/incidents/{id}/comparisons?scope=UPSTREAM&processCode=cvd", ev1009)
			.andExpect(jsonPath("$.data.equipmentId").value("CVD-PM1"));
		getAs(etch, "/api/incidents/{id}/comparisons?scope=SIDEWAYS", ev1009).andExpect(status().isBadRequest());
		getAs(etch, "/api/incidents/{id}/comparisons?scope=UPSTREAM&processCode=PHOTO", ev1009)
			.andExpect(status().isBadRequest()).andExpect(jsonPath("$.error.code").value("INVALID_PROCESS_CODE"));
		getAs(etch, "/api/incidents/{id}/comparisons?scope=UPSTREAM&processCode=CMP", ev1009).andExpect(status().isBadRequest());
	}

	@Test
	void headPressureIsOutsideReferenceBandForL006() throws Exception {
		getAs(etch, "/api/incidents/{id}/comparisons", ev1006)
			.andExpect(jsonPath("$.data.evidence.amountWithinNormalVariation").value(false))
			.andExpect(jsonPath("$.data.features[?(@.sensorId=='head_pressure')].outsideReferenceBand", contains(true)))
			.andExpect(jsonPath("$.data.features[?(@.sensorId=='slurry_flow')].outsideReferenceBand", contains(false)));
	}

	@Test
	void newIncidentGetsStepEvidence() throws Exception {
		String id = newIncident(etch, false).incidentId();
		getAs(etch, "/api/incidents/{id}/comparisons?scope=CURRENT", id)
			.andExpect(jsonPath("$.data.evidence.available").value(true))
			.andExpect(jsonPath("$.data.asOf").exists());
	}

	@Test
	void dataApisEnforceProcessScope() throws Exception {
		Session upstream = cvd();
		for (String path : java.util.List.of("sensor-data", "metrology", "changes", "comparisons")) {
			getAs(upstream, "/api/incidents/{id}/" + path, ev1006).andExpect(status().isForbidden());
		}
	}
}
