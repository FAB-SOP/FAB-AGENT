package com.fabcontext.api.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.Map;

import com.fabcontext.api.support.ApiTestSupport;
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("P11 보고서 초안 · 편집 · 확정 · 내보내기 · 새 버전")
class ReportApiTest extends ApiTestSupport {

	private String draft(Session s, String incidentId, Integer base) throws Exception {
		String body = "{\"expectedIncidentVersion\":" + incidentVersion(s, incidentId)
				+ (base == null ? "" : ",\"baseReportVersion\":" + base) + "}";
		var accepted = postAs(s, "/api/incidents/{id}/report-jobs", body, incidentId).andExpect(status().isAccepted())
			.andExpect(jsonPath("$.data.kind").value("REPORT"));
		String jobId = json(accepted, "$.data.jobId");
		awaitJob(s, jobId, "SUCCEEDED");
		return json(getAs(s, "/api/jobs/{id}", jobId), "$.data.resultRef.id");
	}

	@Test
	void draftIsComposedFromRecordsOnly() throws Exception {
		Session etch = cmp();
		String id = newIncident(etch, false).incidentId();
		getAs(etch, "/api/incidents/{id}/reports", id).andExpect(jsonPath("$.data.items", hasSize(0)));
		String reportId = draft(etch, id, null);
		getAs(etch, "/api/reports/{id}", reportId)
			.andExpect(jsonPath("$.data.status").value("DRAFT"))
			.andExpect(jsonPath("$.data.version").value(1))
			.andExpect(jsonPath("$.data.contentRevision").value(1))
			// 프론트 계약 — 배열 [{key, title, body}], 다섯 키 순서 고정
			.andExpect(jsonPath("$.data.sections[*].key", contains("overview", "investigation", "requestsAndReplies",
				"actionsAndVerification", "unresolved")))
			.andExpect(jsonPath("$.data.sections[0].title").value("사건 개요"))
			.andExpect(jsonPath("$.data.sections[0].body").value(containsString("사건:")))
			.andExpect(jsonPath("$.data.sections[2].body").value(containsString("확인 요청이 없습니다")))
			.andExpect(jsonPath("$.data.sections[3].body").value(containsString("기록된 실제 조치가 없습니다")))
			.andExpect(jsonPath("$.data.sections[1].body").value(containsString("가설 (확정 아님)")))
			.andExpect(jsonPath("$.data.sections[4].body").exists())
			.andExpect(jsonPath("$.data.evidenceIds", not(empty())));
		getAs(etch, "/api/incidents/{id}", id).andExpect(jsonPath("$.data.timeline[*].type", hasItems("REPORT_REQUESTED", "REPORT_DRAFTED")))
			.andExpect(jsonPath("$.data.status").value("INVESTIGATING"));
	}

	@Test
	void editConfirmExportAndNewVersion() throws Exception {
		Session etch = cmp();
		String id = newIncident(etch, false).incidentId();
		String reportId = draft(etch, id, null);
		String sections = JsonPath.parse(getAs(etch, "/api/reports/{id}", reportId).andReturn().getResponse()
			.getContentAsString(java.nio.charset.StandardCharsets.UTF_8)).set("$.data.sections[0].body", "편집된 개요")
			.read("$.data.sections", net.minidev.json.JSONArray.class).toJSONString();

		patchAs(etch, "/api/reports/{id}", "{\"sections\":" + sections + ",\"expectedContentRevision\":7}", reportId)
			.andExpect(status().isConflict()).andExpect(jsonPath("$.error.code").value("REVISION_CONFLICT"))
			.andExpect(jsonPath("$.error.details.currentContentRevision").value(1));
		patchAs(etch, "/api/reports/{id}", "{\"sections\":[],\"expectedContentRevision\":1}", reportId)
			.andExpect(status().is4xxClientError());
		patchAs(etch, "/api/reports/{id}", "{\"sections\":[{\"key\":\"overview\",\"title\":\"개요\",\"body\":\"개요만\"}],\"expectedContentRevision\":1}", reportId)
			.andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.error.code").value("INVALID_SECTIONS"));
		patchAs(etch, "/api/reports/{id}", "{\"title\":\"수정한 제목\",\"sections\":" + sections + ",\"expectedContentRevision\":1}", reportId)
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.title").value("수정한 제목"))
			.andExpect(jsonPath("$.data.contentRevision").value(2))
			.andExpect(jsonPath("$.data.updatedBy").value("USR-CMP-01"))
			.andExpect(jsonPath("$.data.sections[0].body").value("편집된 개요"));

		getAs(etch, "/api/reports/{id}/export", reportId).andExpect(status().isOk())
			.andExpect(content().string(startsWith("<!-- DRAFT")));
		getAs(etch, "/api/reports/{id}/export?format=pdf", reportId).andExpect(status().isUnprocessableEntity())
			.andExpect(jsonPath("$.error.code").value("UNSUPPORTED_FORMAT"));

		postAs(etch, "/api/reports/{id}/confirmations", "{\"expectedContentRevision\":2,\"reviewConfirmed\":false}", reportId)
			.andExpect(jsonPath("$.error.code").value("REVIEW_CONFIRMATION_REQUIRED"));
		postAs(etch, "/api/reports/{id}/confirmations", "{\"expectedContentRevision\":1,\"reviewConfirmed\":true}", reportId)
			.andExpect(status().isConflict());
		postAs(etch, "/api/reports/{id}/confirmations", "{\"expectedContentRevision\":2,\"reviewConfirmed\":true}", reportId)
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.status").value("CONFIRMED"))
			.andExpect(jsonPath("$.data.confirmedBy").value("USR-CMP-01"))
			.andExpect(jsonPath("$.data.snapshotRef").value("snapshot://reports/" + reportId + "/v1/r2"));

		patchAs(etch, "/api/reports/{id}", "{\"sections\":" + sections + ",\"expectedContentRevision\":2}", reportId)
			.andExpect(status().isConflict()).andExpect(jsonPath("$.error.code").value("REPORT_CONFIRMED"));
		postAs(etch, "/api/reports/{id}/confirmations", "{\"expectedContentRevision\":2,\"reviewConfirmed\":true}", reportId)
			.andExpect(status().isConflict());
		var md = getAs(etch, "/api/reports/{id}/export?format=markdown", reportId).andExpect(status().isOk())
			.andExpect(header().string("Content-Disposition", containsString("attachment")))
			.andExpect(header().string("Content-Type", startsWith("text/markdown")))
			.andReturn().getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
		assertThat(md).startsWith("# 수정한 제목").contains("CONFIRMED").contains("편집된 개요").doesNotContain("DRAFT");

		String v2 = draft(etch, id, 1);
		getAs(etch, "/api/reports/{id}", v2).andExpect(jsonPath("$.data.version").value(2))
			.andExpect(jsonPath("$.data.baseReportVersion").value(1))
			.andExpect(jsonPath("$.data.sections[0].body").value(containsString("이전 보고서 v1")));
		getAs(etch, "/api/incidents/{id}/reports", id).andExpect(jsonPath("$.data.items[*].version", contains(2, 1)))
			.andExpect(jsonPath("$.data.items[*].status", contains("DRAFT", "CONFIRMED")));
		getAs(etch, "/api/reports/{id}", reportId).andExpect(jsonPath("$.data.status").value("CONFIRMED"));
	}

	@Test
	void confirmThroughApprovalProposal() throws Exception {
		Session etch = cmp();
		String id = newIncident(etch, false).incidentId();
		String reportId = draft(etch, id, null);
		String approvalId = json(postAs(etch, "/api/incidents/{id}/proposals", """
			{"actionType":"CONFIRM_REPORT","payload":{"reportId":"%s","expectedContentRevision":1},"rationale":"검토 완료","expectedIncidentVersion":%d}
			""".formatted(reportId, incidentVersion(etch, id)), id).andExpect(status().isCreated()), "$.data.approvalId");
		postAs(etch, "/api/approvals/{id}/decisions", "{\"decision\":\"APPROVE\",\"expectedVersion\":1}", approvalId)
			.andExpect(jsonPath("$.data.executionRef.type").value("REPORT"));
		getAs(etch, "/api/reports/{id}", reportId).andExpect(jsonPath("$.data.status").value("CONFIRMED"));
	}

	@Test
	void generationValidationAndScope() throws Exception {
		Session etch = cmp();
		String id = newIncident(etch, false).incidentId();
		postAs(etch, "/api/incidents/{id}/report-jobs", "{\"expectedIncidentVersion\":" + incidentVersion(etch, id) + ",\"baseReportVersion\":9}", id)
			.andExpect(jsonPath("$.error.code").value("BASE_REPORT_NOT_FOUND"));
		postAs(etch, "/api/incidents/{id}/report-jobs", "{\"expectedIncidentVersion\":0}", id).andExpect(status().isConflict());
		String reportId = json(getAs(etch, "/api/incidents/{id}/reports", seededIncident(etch, "EV-1021")), "$.data.items[0].reportId");
		getAs(cvd(), "/api/reports/{id}", reportId).andExpect(status().isForbidden());
		getAs(cvd(), "/api/reports/{id}/export", reportId).andExpect(status().isForbidden());
		getAs(etch, "/api/reports/{id}", "rpt_missing").andExpect(status().isNotFound());
	}
}
