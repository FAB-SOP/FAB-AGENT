package com.fabcontext.api.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.util.List;

import com.fabcontext.api.support.ApiTestSupport;
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("003 확정본 AI 요약 — 확정과 함께 접수 · 시뮬레이터 규칙 요약 · 실패 · 재요청 · 알림")
class ReportAiSummaryApiTest extends ApiTestSupport {

	private String draft(Session s, String incidentId) throws Exception {
		String jobId = json(postAs(s, "/api/incidents/{id}/report-jobs",
			"{\"expectedIncidentVersion\":" + incidentVersion(s, incidentId) + "}", incidentId)
			.andExpect(status().isAccepted()), "$.data.jobId");
		awaitJob(s, jobId, "SUCCEEDED");
		return json(getAs(s, "/api/jobs/{id}", jobId), "$.data.resultRef.id");
	}

	private static String confirmBody(long revision) {
		return "{\"expectedContentRevision\":" + revision + ",\"reviewConfirmed\":true,\"classification\":{\"category\":\"METHOD\"}}";
	}

	private String awaitSummary(Session s, String reportId, String... expected) throws Exception {
		List<String> wanted = List.of(expected);
		String last = null;
		for (int i = 0; i < 200; i++) {
			last = json(getAs(s, "/api/reports/{id}", reportId), "$.data.summary.status");
			if (wanted.contains(last)) {
				return last;
			}
			Thread.sleep(25);
		}
		assertThat(last).as("summary of %s", reportId).isIn(wanted);
		return last;
	}

	@Test
	void confirmationQueuesSummaryWhichTheSimulatorCompletesByRule() throws Exception {
		Session engineer = cmp();
		String incidentId = newIncident(engineer, false).incidentId();
		String displayId = json(getAs(engineer, "/api/incidents/{id}", incidentId), "$.data.displayId");
		String jobStatus = json(getAs(engineer, "/api/incidents?q=" + displayId), "$.data.items[0].jobStatus");
		String reportId = draft(engineer, incidentId);
		getAs(engineer, "/api/reports/{id}", reportId).andExpect(jsonPath("$.data.summary").value(nullValue()));

		postAs(engineer, "/api/reports/{id}/confirmations", confirmBody(1), reportId).andExpect(status().isOk())
			.andExpect(jsonPath("$.data.summary.status").value("PENDING"));
		awaitSummary(engineer, reportId, "READY");

		String body = getAs(engineer, "/api/reports/{id}", reportId).andReturn().getResponse()
			.getContentAsString(StandardCharsets.UTF_8);
		List<String> lines = JsonPath.read(body, "$.data.summary.lines");
		assertThat(lines).hasSizeBetween(1, 2).allSatisfy(line -> assertThat(line).isNotBlank().hasSizeLessThanOrEqualTo(120));
		assertThat((String) JsonPath.read(body, "$.data.summary.generatedBy")).isEqualTo("rule");
		assertThat((List<String>) JsonPath.read(body, "$.data.summary.evidenceIds")).isNotNull();
		getAs(engineer, "/api/incidents/{id}/reports", incidentId)
			.andExpect(jsonPath("$.data.items[0].summary.status").value("READY"));
		// 요약 작업은 조사 상태를 바꾸지 않는다 — 닫힌 사건이 「분석 중」으로 보이지 않는다
		getAs(engineer, "/api/incidents/{id}", incidentId)
			.andExpect(jsonPath("$.data.status").value("CLOSED"))
			.andExpect(jsonPath("$.data.activeJobId").value(nullValue()));
		getAs(engineer, "/api/incidents?q=" + displayId).andExpect(jsonPath("$.data.items[0].jobStatus").value(jobStatus));
		getAs(engineer, "/api/notifications?size=100")
			.andExpect(jsonPath("$.data.items[*].type", hasItem("REPORT_SUMMARY_READY")));
		// 완료된 요약은 다시 요청할 수 없다
		postAs(engineer, "/api/reports/{id}/summary-jobs", "", reportId)
			.andExpect(status().isConflict()).andExpect(jsonPath("$.error.code").value("SUMMARY_NOT_RETRYABLE"));
	}

	@Test
	void failedSummaryIsShownWithoutTimelineAndCanBeRequestedAgain() throws Exception {
		Session engineer = cmp();
		String incidentId = newIncident(engineer, false).incidentId();
		String reportId = draft(engineer, incidentId);
		// 시뮬레이터 실패 주입 — 작업 질문(보고서 제목)에 [fail]
		String sections = JsonPath.parse(getAs(engineer, "/api/reports/{id}", reportId).andReturn().getResponse()
			.getContentAsString(StandardCharsets.UTF_8)).read("$.data.sections", net.minidev.json.JSONArray.class)
			.toJSONString();
		patchAs(engineer, "/api/reports/{id}", "{\"title\":\"요약 실패 시험 [fail]\",\"sections\":" + sections
				+ ",\"expectedContentRevision\":1}", reportId).andExpect(status().isOk());

		postAs(engineer, "/api/reports/{id}/confirmations", confirmBody(2), reportId).andExpect(status().isOk());
		awaitSummary(engineer, reportId, "FAILED");
		getAs(engineer, "/api/reports/{id}", reportId)
			.andExpect(jsonPath("$.data.summary.error").value("요약을 만들지 못했습니다. 다시 요청할 수 있습니다."))
			.andExpect(jsonPath("$.data.summary.lines").isEmpty());
		getAs(engineer, "/api/incidents/{id}", incidentId)
			.andExpect(jsonPath("$.data.timeline[*].type", not(hasItem("ANALYSIS_FAILED"))));
		getAs(engineer, "/api/notifications?size=100")
			.andExpect(jsonPath("$.data.items[*].type", hasItem("REPORT_SUMMARY_FAILED")));

		// 다른 공정 사용자는 다시 요청할 수 없다 · 종료된 사건이어도 확정 권한자는 다시 요청할 수 있다
		postAs(cvd(), "/api/reports/{id}/summary-jobs", "", reportId).andExpect(status().isForbidden());
		postAs(engineer, "/api/reports/{id}/summary-jobs", "", reportId).andExpect(status().isAccepted())
			.andExpect(jsonPath("$.data.summary.status").value("PENDING"));
		awaitSummary(engineer, reportId, "FAILED");
	}

	@Test
	void legacyAndDraftReportsHaveNoSummaryAndCannotBeRetried() throws Exception {
		Session engineer = cmp();
		String incidentId = newIncident(engineer, false).incidentId();
		String reportId = draft(engineer, incidentId);
		postAs(engineer, "/api/reports/{id}/summary-jobs", "", reportId)
			.andExpect(status().isConflict()).andExpect(jsonPath("$.error.code").value("SUMMARY_NOT_RETRYABLE"));
	}
}
