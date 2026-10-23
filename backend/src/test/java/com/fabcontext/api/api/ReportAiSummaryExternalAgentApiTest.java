package com.fabcontext.api.api;

import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.hasItem;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.Map;

import com.fabcontext.api.support.ApiTestSupport;
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.ResultActions;

/**
 * 외부 Agent 모드 — 시뮬레이터 없이 내부 API로 요약 작업을 직접 진행해, 늦게 온 결과 · 다른 확정본의 결과를 결정적으로 시험한다.
 * 시드의 EV-1021 확정 보고서(확정자 USR-CMP-01)는 확정과 함께 SUMMARY 작업이 QUEUED로 남는다. 자기 스키마(backend_ext)에 따로 시드한다.
 */
@DisplayName("003 확정본 요약 — 외부 Agent 계약(작업 메시지 · 확정본 조회 · SUMMARY_READY 대조)")
@TestPropertySource(properties = {"fab.agent.mode=external",
		"spring.jpa.properties.hibernate.default_schema=backend_ext", "spring.datasource.hikari.schema=backend_ext"})
class ReportAiSummaryExternalAgentApiTest extends ApiTestSupport {

	static final String BEARER = "Bearer dev-service-token";

	private ResultActions iget(String url, Object... vars) throws Exception {
		return mvc.perform(get(url, vars).header("Authorization", BEARER));
	}

	private ResultActions event(String jobId, String type, String payload) throws Exception {
		return mvc.perform(post("/internal/jobs/{id}/events", jobId).header("Authorization", BEARER)
			.contentType(MediaType.APPLICATION_JSON).content("""
				{"schemaVersion":"1.0","eventId":"EVT-%s-%s-%d","jobId":"%s","attempt":1,"type":"%s","payload":%s}
				""".formatted(jobId, type, System.nanoTime(), jobId, type, payload)));
	}

	private static String ready(String reportId, String snapshotRef, String line) {
		return """
			{"reportId":"%s","snapshotRef":"%s","summary":{"lines":["%s"],"evidenceIds":[],"generatedBy":"model"}}
			""".formatted(reportId, snapshotRef, line);
	}

	@Test
	void summaryJobFollowsTheTeamContractAndOnlyTheCurrentResultIsSaved() throws Exception {
		Session engineer = cmp();
		String incidentId = seededIncident(engineer, "EV-1021");
		String reportId = json(getAs(engineer, "/api/incidents/{id}/reports", incidentId), "$.data.items[0].reportId");
		String snapshotRef = json(getAs(engineer, "/api/reports/{id}", reportId), "$.data.snapshotRef");
		getAs(engineer, "/api/reports/{id}", reportId).andExpect(jsonPath("$.data.summary.status").value("PENDING"));

		// 작업 메시지: SUMMARY는 input.summaryRef만 싣는다
		String jobs = iget("/internal/jobs").andReturn().getResponse().getContentAsString();
		List<Map<String, Object>> summaryJobs = JsonPath.read(jobs,
			"$.data[?(@.job.kind == 'SUMMARY' && @.job.incidentId == '" + incidentId + "')].job");
		Map<String, Object> job = summaryJobs.getFirst();
		String jobId = (String) job.get("jobId");
		@SuppressWarnings("unchecked")
		Map<String, Object> summaryRef = (Map<String, Object>) ((Map<String, Object>) job.get("input")).get("summaryRef");
		org.assertj.core.api.Assertions.assertThat((Map<String, Object>) job.get("input")).containsOnlyKeys("summaryRef");
		org.assertj.core.api.Assertions.assertThat(summaryRef).containsEntry("reportId", reportId)
			.containsEntry("snapshotRef", snapshotRef).containsKeys("reportVersion", "contentRevision");

		// 확정본: 칸 이름별 글 · 표시 ID · 분류
		iget("/internal/reports/{id}/snapshot", reportId).andExpect(status().isOk())
			.andExpect(jsonPath("$.data.reportId").value(reportId))
			.andExpect(jsonPath("$.data.snapshotRef").value(snapshotRef))
			.andExpect(jsonPath("$.data.incidentDisplayId").value("EV-1021"))
			.andExpect(jsonPath("$.data.sections.overview").isString())
			.andExpect(jsonPath("$.data.sections.unresolved").isString())
			.andExpect(jsonPath("$.data.classification.category").value("MACHINE"))
			.andExpect(jsonPath("$.data.classification.equipmentParameters", contains("slurry_flow")))
			.andExpect(jsonPath("$.data.confirmedBy").value("USR-CMP-01"));
		mvc.perform(get("/internal/reports/{id}/snapshot", reportId)).andExpect(status().isUnauthorized());

		event(jobId, "JOB_STARTED", "{}").andExpect(status().isOk());
		getAs(engineer, "/api/reports/{id}", reportId).andExpect(jsonPath("$.data.summary.status").value("RUNNING"));

		// 다른 확정본의 결과 → 저장하지 않고 작업을 닫는다. 요약은 실패로 남아 다시 요청할 수 있다
		event(jobId, "SUMMARY_READY", ready(reportId, snapshotRef + "-old", "다른 확정본 요약")).andExpect(status().isOk());
		getAs(engineer, "/api/jobs/{id}", jobId).andExpect(jsonPath("$.data.status").value("FAILED"))
			.andExpect(jsonPath("$.data.error.code").value("STALE_RESULT"));
		getAs(engineer, "/api/reports/{id}", reportId).andExpect(jsonPath("$.data.summary.status").value("FAILED"))
			.andExpect(jsonPath("$.data.summary.lines").isEmpty());

		String retried = json(postAs(engineer, "/api/reports/{id}/summary-jobs", "", reportId).andExpect(status().isAccepted())
			.andExpect(jsonPath("$.data.summary.status").value("PENDING")), "$.data.reportId");
		org.assertj.core.api.Assertions.assertThat(retried).isEqualTo(reportId);
		String jobs2 = iget("/internal/jobs").andReturn().getResponse().getContentAsString();
		List<String> newJobIds = JsonPath.read(jobs2,
			"$.data[?(@.job.kind == 'SUMMARY' && @.job.incidentId == '" + incidentId + "')].job.jobId");
		String newJobId = newJobIds.getFirst();
		org.assertj.core.api.Assertions.assertThat(newJobId).isNotEqualTo(jobId);

		// 늦게 도착한 예전 작업의 결과는 현재 요약을 바꾸지 않는다
		event(jobId, "SUMMARY_READY", ready(reportId, snapshotRef, "예전 작업 결과")).andExpect(status().isOk())
			.andExpect(jsonPath("$.data.applied").value(false));
		getAs(engineer, "/api/reports/{id}", reportId).andExpect(jsonPath("$.data.summary.status").value("PENDING"));

		// 줄 수 · 길이는 서버가 다시 검사한다
		event(newJobId, "SUMMARY_READY", """
			{"reportId":"%s","snapshotRef":"%s","summary":{"lines":["a","b","c"],"evidenceIds":[],"generatedBy":"model"}}
			""".formatted(reportId, snapshotRef)).andExpect(status().is4xxClientError());

		event(newJobId, "SUMMARY_READY", ready(reportId, snapshotRef, "슬러리 유량 저하로 두께 이상 — 공급 라인 점검 · 필터 교체로 회복"))
			.andExpect(status().isOk()).andExpect(jsonPath("$.data.applied").value(true));
		getAs(engineer, "/api/reports/{id}", reportId)
			.andExpect(jsonPath("$.data.summary.status").value("READY"))
			.andExpect(jsonPath("$.data.summary.lines", contains("슬러리 유량 저하로 두께 이상 — 공급 라인 점검 · 필터 교체로 회복")))
			.andExpect(jsonPath("$.data.summary.generatedBy").value("model"));
		getAs(engineer, "/api/notifications?size=100")
			.andExpect(jsonPath("$.data.items[*].type", hasItem("REPORT_SUMMARY_READY")));
		// 완료 뒤 같은 작업의 중복 이벤트 · 재요청은 덮어쓰지 않는다
		event(newJobId, "SUMMARY_READY", ready(reportId, snapshotRef, "덮어쓰기 시도")).andExpect(jsonPath("$.data.applied").value(false));
		postAs(engineer, "/api/reports/{id}/summary-jobs", "", reportId).andExpect(status().isConflict());
		getAs(engineer, "/api/reports/{id}", reportId)
			.andExpect(jsonPath("$.data.summary.lines", contains("슬러리 유량 저하로 두께 이상 — 공급 라인 점검 · 필터 교체로 회복")));
	}

	@Test
	void unknownReportHasNoSnapshot() throws Exception {
		iget("/internal/reports/{id}/snapshot", "rpt_missing").andExpect(status().isNotFound());
	}
}
