package com.fabcontext.api.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;

import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import com.fabcontext.api.support.ApiTestSupport;

@DisplayName("P11 보고서 목록 · 제목 검색 — 서버 조회 · 페이지 · 필터 · 권한")
class ReportSearchApiTest extends ApiTestSupport {

	@Autowired
	JdbcTemplate jdbc;

	private record CreatedReport(String reportId, String incidentId, String displayId, LocalDate detectedDay) {
	}

	private CreatedReport draft(Session s) throws Exception {
		String incidentId = newIncident(s, false).incidentId();
		var accepted = postAs(s, "/api/incidents/{id}/report-jobs",
			"{\"expectedIncidentVersion\":" + incidentVersion(s, incidentId) + "}", incidentId)
			.andExpect(status().isAccepted());
		String jobId = json(accepted, "$.data.jobId");
		awaitJob(s, jobId, "SUCCEEDED");
		String reportId = json(getAs(s, "/api/jobs/{id}", jobId), "$.data.resultRef.id");
		var incident = getAs(s, "/api/incidents/{id}", incidentId);
		Instant detectedAt = Instant.parse(json(incident, "$.data.detectedAt"));
		return new CreatedReport(reportId, incidentId, json(incident, "$.data.displayId"),
			detectedAt.atZone(ZoneId.of("Asia/Seoul")).toLocalDate());
	}

	private void confirm(Session s, CreatedReport report, String classification) throws Exception {
		postAs(s, "/api/reports/{id}/confirmations",
			"{\"expectedContentRevision\":1,\"reviewConfirmed\":true,\"classification\":" + classification + "}",
			report.reportId()).andExpect(status().isOk());
	}

	/** 조건에 맞는 보고서 ID를 모든 페이지에서 모은다 — 다른 테스트의 보고서도 섞여 있으므로 포함 여부로 판단한다 */
	private List<String> reportIds(Session s, String query) throws Exception {
		List<String> ids = new ArrayList<>();
		for (int page = 0; ; page++) {
			String body = getAs(s, "/api/reports?processCode=CMP&size=100&page=" + page + query)
				.andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
			ids.addAll(JsonPath.read(body, "$.data.items[*].reportId"));
			if (!(Boolean) JsonPath.read(body, "$.data.hasNext")) {
				return ids;
			}
		}
	}

	@Test
	void listIsOneServerQueryPagedNewestFirst() throws Exception {
		mvc.perform(get("/api/reports").param("processCode", "CMP")).andExpect(status().isUnauthorized());
		getAs(cvd(), "/api/reports?processCode=CMP").andExpect(status().isForbidden());
		Session engineer = cmp();
		getAs(engineer, "/api/reports?processCode=PHOTO").andExpect(status().isUnprocessableEntity())
			.andExpect(jsonPath("$.error.code").value("INVALID_PROCESS_CODE"));
		getAs(engineer, "/api/reports").andExpect(status().isUnprocessableEntity());
		getAs(engineer, "/api/reports?processCode=CMP&size=101").andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.error.code").value("INVALID_PAGE_SIZE"));
		draft(engineer);
		draft(engineer);

		var first = getAs(engineer, "/api/reports?processCode=CMP&size=1&page=0").andExpect(status().isOk())
			.andExpect(jsonPath("$.data.page").value(0))
			.andExpect(jsonPath("$.data.size").value(1))
			.andExpect(jsonPath("$.data.hasNext").value(true))
			.andExpect(jsonPath("$.data.items.length()").value(1));
		var second = getAs(engineer, "/api/reports?processCode=CMP&size=1&page=1").andExpect(status().isOk());
		assertThat(json(second, "$.data.items[0].reportId").toString())
			.isNotEqualTo(json(first, "$.data.items[0].reportId"));

		String body = getAs(engineer, "/api/reports?processCode=CMP&size=100").andReturn().getResponse()
			.getContentAsString();
		List<String> detected = JsonPath.read(body, "$.data.items[*].detectedAt");
		List<String> processes = JsonPath.read(body, "$.data.items[*].processCode");
		assertThat(detected.stream().map(Instant::parse).toList()).isSortedAccordingTo((a, b) -> b.compareTo(a));
		assertThat(processes).containsOnly("CMP");
		// 시드 보고서 — 확정 때 Machine · 슬러리 유량으로 분류됨
		List<String> seededCategories = JsonPath.read(body,
			"$.data.items[?(@.classification.category == 'MACHINE')].classification.equipmentParameters[0]");
		assertThat(seededCategories).contains("slurry_flow");
	}

	@Test
	void titleSearchIsPartialCaseInsensitiveAndLiteral() throws Exception {
		Session engineer = cmp();
		CreatedReport report = draft(engineer);

		assertThat(reportIds(engineer, "&q=" + report.displayId())).contains(report.reportId());
		assertThat(reportIds(engineer, "&q=" + report.displayId().toLowerCase())).contains(report.reportId());
		getAs(engineer, "/api/reports?processCode=CMP&q=" + report.displayId())
			.andExpect(jsonPath("$.data.items[*].title").value(org.hamcrest.Matchers.everyItem(
				org.hamcrest.Matchers.containsString(report.displayId()))));
		getAs(engineer, "/api/reports?processCode=CMP&q=존재하지않는제목ZZ").andExpect(jsonPath("$.data.items").isEmpty())
			.andExpect(jsonPath("$.data.hasNext").value(false));
		// %와 _는 와일드카드가 아니라 글자 그대로 — 제목에 그 글자가 없으니 결과가 없다
		getAs(engineer, "/api/reports?processCode=CMP&q=%25").andExpect(jsonPath("$.data.items").isEmpty());
		getAs(engineer, "/api/reports?processCode=CMP&q=_").andExpect(jsonPath("$.data.items").isEmpty());
		getAs(engineer, "/api/reports?processCode=CMP&q=" + "가".repeat(101))
			.andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.error.code").value("INVALID_KEYWORD"));
		// 다른 공정을 보는 사용자는 검색어가 맞아도 볼 수 없다
		getAs(cvd(), "/api/reports?processCode=CMP&q=" + report.displayId()).andExpect(status().isForbidden());
	}

	@Test
	void filtersApplyOnServerTogetherWithSearch() throws Exception {
		Session engineer = cmp();
		CreatedReport method = draft(engineer);
		CreatedReport hold = draft(engineer);
		CreatedReport machine = draft(engineer);
		CreatedReport stillDraft = draft(engineer);
		confirm(engineer, method, "{\"category\":\"METHOD\"}");
		confirm(engineer, hold, "{\"category\":\"HOLD\"}");
		confirm(engineer, machine, "{\"category\":\"MACHINE\",\"equipmentParameters\":[\"head_pressure\"]}");

		assertThat(reportIds(engineer, "&category=METHOD")).contains(method.reportId())
			.doesNotContain(hold.reportId(), machine.reportId(), stillDraft.reportId());
		assertThat(reportIds(engineer, "&category=HOLD")).contains(hold.reportId()).doesNotContain(method.reportId());
		assertThat(reportIds(engineer, "&category=MACHINE&equipmentParameter=head_pressure")).contains(machine.reportId());
		assertThat(reportIds(engineer, "&category=MACHINE&equipmentParameter=slurry_flow")).doesNotContain(machine.reportId());
		assertThat(reportIds(engineer, "&category=UNCLASSIFIED")).contains(stillDraft.reportId())
			.doesNotContain(method.reportId(), hold.reportId(), machine.reportId());
		// 검색 결과에 대해서만 필터가 적용된다 — 교집합
		assertThat(reportIds(engineer, "&q=" + method.displayId() + "&category=METHOD")).containsExactly(method.reportId());
		assertThat(reportIds(engineer, "&q=" + method.displayId() + "&category=HOLD")).isEmpty();

		LocalDate day = method.detectedDay();
		assertThat(reportIds(engineer, "&detectedFrom=" + day + "&detectedTo=" + day)).contains(method.reportId());
		assertThat(reportIds(engineer, "&detectedTo=" + day.minusDays(1))).doesNotContain(method.reportId());
		assertThat(reportIds(engineer, "&detectedFrom=" + day.plusDays(1))).doesNotContain(method.reportId());
		getAs(engineer, "/api/reports?processCode=CMP&detectedFrom=" + day + "&detectedTo=" + day.minusDays(1))
			.andExpect(status().isUnprocessableEntity())
			.andExpect(jsonPath("$.error.code").value("INVALID_DETECTED_RANGE"));
		getAs(engineer, "/api/reports?processCode=CMP&category=MACHINERY").andExpect(status().isUnprocessableEntity());
	}

	@Test
	void searchIndexesExistAndSchemaScriptIsRerunnable() throws Exception {
		List<String> indexes = jdbc.queryForList("""
			select indexname from pg_indexes where schemaname = current_schema()
			and indexname in ('idx_report_title_trgm', 'idx_incident_process_detected')""", String.class);
		assertThat(indexes).containsExactlyInAnyOrder("idx_report_title_trgm", "idx_incident_process_detected");
		assertThat(jdbc.queryForObject("select indexdef from pg_indexes where indexname = 'idx_report_title_trgm' "
				+ "and schemaname = current_schema()", String.class)).contains("gin").contains("gin_trgm_ops");
		// 서버를 다시 띄우면 schema.sql이 또 돈다 — 같은 스크립트를 다시 실행해도 오류 · 중복이 없어야 한다
		String script = new ClassPathResource("schema.sql").getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
		jdbc.execute(script);
		assertThat(jdbc.queryForList("""
			select indexname from pg_indexes where schemaname = current_schema()
			and indexname in ('idx_report_title_trgm', 'idx_incident_process_detected')""", String.class)).hasSize(2);
	}
}
