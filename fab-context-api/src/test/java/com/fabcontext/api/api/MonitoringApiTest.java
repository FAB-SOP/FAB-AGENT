package com.fabcontext.api.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.zip.GZIPInputStream;

import com.fabcontext.api.support.ApiTestSupport;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * 모니터링 GET 3개를 프론트 전달 묶음의 응답 예시(backend-handoff-v2/examples)와 필드 · 값 단위로 대조한다(L006은 v2 값).
 * 의도한 차이는 넷뿐이다 — {@link #ALLOWED}.
 */
@DisplayName("모니터링 API (프론트 예시 대조)")
class MonitoringApiTest extends ApiTestSupport {

	static final Path EXAMPLES = Path.of("../backend-handoff-v2/examples");

	static final JsonMapper MAPPER = JsonMapper.builder().build();

	/**
	 * 예시와 다르게 두는 것:
	 * <ul>
	 * <li>runs[].incidentId — 예시는 프론트 모의 ID(INC-RUN-…), 서버는 실제 사건 ID(inc_…). 값의 유무(정상 run은 null)만 같아야 한다</li>
	 * <li>samples[].sensorName — 팩에 센서 한국어 이름 목록이 없어 서버는 sensorId를 그대로 낸다(이름을 지어내지 않는다)</li>
	 * <li>samples[].quality — 원본 trace의 GOOD_SYNTHETIC을 그대로 낸다. 예시는 GOOD. 명세: 둘 다 정상 취득</li>
	 * <li>samples[].measuredAt — 서버는 UTC(Z), 예시는 +09:00. 같은 시각이면 같다</li>
	 * </ul>
	 */
	static final Set<String> ALLOWED = Set.of("incidentId", "sensorName", "quality", "measuredAt");

	private JsonNode example(String name) throws Exception {
		Path p = EXAMPLES.resolve(name);
		try (InputStream in = name.endsWith(".gz") ? new GZIPInputStream(Files.newInputStream(p)) : Files.newInputStream(p)) {
			return MAPPER.readTree(in);
		}
	}

	private JsonNode actual(Session s, String url, Object... vars) throws Exception {
		String body = getAs(s, url, vars).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
		return MAPPER.readTree(body);
	}

	/** 예시의 모든 필드가 같은 값으로 응답에 있는지 본다(응답이 더 주는 필드는 허용) */
	static void compare(JsonNode expected, JsonNode actual, String path, List<String> diffs) {
		String field = path.substring(path.lastIndexOf('.') + 1).replaceAll("\\[\\d+]$", "");
		if (ALLOWED.contains(field)) {
			switch (field) {
				case "incidentId" -> {
					if (expected.isNull() != actual.isNull()) {
						diffs.add(path + ": 사건 유무 " + expected + " ↔ " + actual);
					}
				}
				case "quality" -> {
					if (!Set.of("GOOD", "GOOD_SYNTHETIC").contains(actual.asString())) {
						diffs.add(path + ": " + actual);
					}
				}
				case "measuredAt" -> {
					if (!OffsetDateTime.parse(expected.asString()).toInstant().equals(OffsetDateTime.parse(actual.asString()).toInstant())) {
						diffs.add(path + ": " + expected + " ↔ " + actual);
					}
				}
				default -> {
				}
			}
			return;
		}
		if (actual == null || actual.isMissingNode()) {
			diffs.add(path + ": 응답에 없음");
			return;
		}
		if (expected.isObject()) {
			for (Iterator<Map.Entry<String, JsonNode>> it = expected.properties().iterator(); it.hasNext();) {
				Map.Entry<String, JsonNode> e = it.next();
				compare(e.getValue(), actual.path(e.getKey()), path + "." + e.getKey(), diffs);
			}
		}
		else if (expected.isArray()) {
			if (expected.size() != actual.size()) {
				diffs.add(path + ": 개수 " + expected.size() + " ↔ " + actual.size());
				return;
			}
			for (int i = 0; i < expected.size(); i++) {
				compare(expected.get(i), actual.get(i), path + "[" + i + "]", diffs);
			}
		}
		else if (expected.isNumber()) {
			if (!actual.isNumber() || Math.abs(expected.asDouble() - actual.asDouble()) > 1e-9 * Math.max(1, Math.abs(expected.asDouble()))) {
				diffs.add(path + ": " + expected + " ↔ " + actual);
			}
		}
		else if (!expected.equals(actual)) {
			diffs.add(path + ": " + expected + " ↔ " + actual);
		}
	}

	@Test
	void lotListMatchesExample() throws Exception {
		List<String> diffs = new ArrayList<>();
		compare(example("lot-list.json").get("data"), actual(cmp(), "/api/monitoring/lots?processCode=CMP").get("data"), "data", diffs);
		assertThat(diffs).isEmpty();
	}

	@ParameterizedTest
	@ValueSource(strings = {"CURRENT-L001", "CURRENT-L006", "CURRENT-L007", "CURRENT-L009"})
	void summaryMatchesExample(String lot) throws Exception {
		List<String> diffs = new ArrayList<>();
		JsonNode got = actual(cmp(), "/api/monitoring/lots/{lot}/summary?processCode=CMP", lot).get("data");
		compare(example(lot + "-CMP-summary.json").get("data"), got, "data", diffs);
		assertThat(diffs).isEmpty();
		assertThat(got.get("dataVersion").asString()).isEqualTo("causal-pack-v2-scenarios");
	}

	@Test
	void l006IsV2AndOnlyOocRunsHaveIncidents() throws Exception {
		Session cmp = cmp();
		getAs(cmp, "/api/monitoring/lots/CURRENT-L006/summary?processCode=CMP")
			.andExpect(jsonPath("$.data.runs[2].equipmentId").value("CMP-PM3"))
			.andExpect(jsonPath("$.data.runs[2].outputMeanA").value(10281.392216))
			.andExpect(jsonPath("$.data.runs[2].addedOrRemovedA").value(4749.009449))
			.andExpect(jsonPath("$.data.runs[2].incidentId").value(startsWith("inc_")))
			.andExpect(jsonPath("$.data.runs[0].incidentId").value(nullValue()))
			.andExpect(jsonPath("$.data.runs[2].features[?(@.sensorId=='head_pressure')].mean").value(contains(2.841574)))
			.andExpect(jsonPath("$.data.runs[2].sites", hasSize(5)))
			.andExpect(jsonPath("$.data.sensorEnvelopes[?(@.sensorId=='head_pressure')].ruleVersion").value(contains("DEMO-SEGMENT-V2")))
			.andExpect(jsonPath("$.data.limits.lsl").value(9000));
		// 규격이 등록되지 않은 공정은 null — 0이나 가상 값을 만들지 않는다
		getAs(manager(), "/api/monitoring/lots/CURRENT-L006/summary?processCode=ETCH")
			.andExpect(jsonPath("$.data.limits.lsl").value(nullValue())).andExpect(jsonPath("$.data.runs", hasSize(3)));
	}

	@Test
	void sensorDataMatchesFullExample() throws Exception {
		JsonNode ex = example("sensor-data-full.json.gz").get("data");
		JsonNode got = actual(cmp(), "/api/monitoring/runs/RUN-W-CURRENT-006-3-CMP/sensor-data?sensorId=head_pressure").get("data");
		List<String> diffs = new ArrayList<>();
		compare(ex, got, "data", diffs);
		assertThat(diffs).isEmpty();
		assertThat(got.get("totalCount").asInt()).isEqualTo(3201);
		assertThat(got.get("truncated").asBoolean()).isFalse(); // 기본 limit 5000
		assertThat(got.get("baseline").isNull()).isTrue();
	}

	@Test
	void statusCodesFollowSpec() throws Exception {
		mvc.perform(get("/api/monitoring/lots").param("processCode", "CMP")).andExpect(status().isUnauthorized());
		Session cmp = cmp();
		getAs(cmp, "/api/monitoring/lots?processCode=ETCH").andExpect(status().isForbidden());
		getAs(cmp, "/api/monitoring/lots?processCode=PHOTO").andExpect(status().isUnprocessableEntity());
		getAs(cmp, "/api/monitoring/lots").andExpect(status().isUnprocessableEntity());
		getAs(cmp, "/api/monitoring/lots/CURRENT-L099/summary?processCode=CMP").andExpect(status().isNotFound());
		getAs(cmp, "/api/monitoring/runs/RUN-W-CURRENT-006-3-ETCH/sensor-data?sensorId=gas_CF4").andExpect(status().isForbidden());
		getAs(cmp, "/api/monitoring/runs/RUN-NOPE/sensor-data?sensorId=head_pressure").andExpect(status().isNotFound());
		getAs(cmp, "/api/monitoring/runs/RUN-W-CURRENT-006-3-CMP/sensor-data?sensorId=gas_CF4").andExpect(status().isNotFound());
		getAs(cmp, "/api/monitoring/runs/RUN-W-CURRENT-006-3-CMP/sensor-data").andExpect(status().isUnprocessableEntity());
		getAs(cmp, "/api/monitoring/runs/RUN-W-CURRENT-006-3-CMP/sensor-data?sensorId=head_pressure&limit=5001")
			.andExpect(status().isUnprocessableEntity());
		getAs(cmp, "/api/monitoring/runs/RUN-W-CURRENT-006-3-CMP/sensor-data?sensorId=head_pressure&limit=100")
			.andExpect(jsonPath("$.data.samples", hasSize(100))).andExpect(jsonPath("$.data.truncated").value(true))
			.andExpect(jsonPath("$.data.totalCount").value(3201));
	}
}
