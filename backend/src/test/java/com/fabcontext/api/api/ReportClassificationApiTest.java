package com.fabcontext.api.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import com.fabcontext.api.support.ApiTestSupport;

@DisplayName("P11 보고서 원인 분류(4M · 보류) — 직접 확정 · 승인 확정 · 표시")
class ReportClassificationApiTest extends ApiTestSupport {

	private String draft(Session s, String incidentId) throws Exception {
		var accepted = postAs(s, "/api/incidents/{id}/report-jobs",
			"{\"expectedIncidentVersion\":" + incidentVersion(s, incidentId) + "}", incidentId)
			.andExpect(status().isAccepted());
		String jobId = json(accepted, "$.data.jobId");
		awaitJob(s, jobId, "SUCCEEDED");
		return json(getAs(s, "/api/jobs/{id}", jobId), "$.data.resultRef.id");
	}

	private static String confirmBody(long revision, String classification) {
		return "{\"expectedContentRevision\":" + revision + ",\"reviewConfirmed\":true"
				+ (classification == null ? "" : ",\"classification\":" + classification) + "}";
	}

	@Test
	void confirmRequiresClassificationAndKeepsIt() throws Exception {
		Session engineer = cmp();
		String incidentId = newIncident(engineer, false).incidentId();
		String reportId = draft(engineer, incidentId);
		getAs(engineer, "/api/reports/{id}", reportId).andExpect(jsonPath("$.data.classification").value(nullValue()));

		postAs(engineer, "/api/reports/{id}/confirmations", confirmBody(1, null), reportId)
			.andExpect(status().isUnprocessableEntity())
			.andExpect(jsonPath("$.error.code").value("CAUSE_CLASSIFICATION_REQUIRED"));
		postAs(engineer, "/api/reports/{id}/confirmations", confirmBody(1, "{\"category\":\"MACHINERY\"}"), reportId)
			.andExpect(jsonPath("$.error.code").value("INVALID_CAUSE_CATEGORY"));
		// 판본 충돌이 분류 오류보다 먼저 — 기존 실패 경로의 응답을 바꾸지 않는다
		postAs(engineer, "/api/reports/{id}/confirmations", confirmBody(9, null), reportId)
			.andExpect(status().isConflict()).andExpect(jsonPath("$.error.code").value("REVISION_CONFLICT"));
		getAs(engineer, "/api/reports/{id}", reportId).andExpect(jsonPath("$.data.status").value("DRAFT"));

		postAs(engineer, "/api/reports/{id}/confirmations",
			confirmBody(1, "{\"category\":\"METHOD\",\"equipmentParameters\":[\"head_pressure\"]}"), reportId)
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.status").value("CONFIRMED"))
			.andExpect(jsonPath("$.data.classification.category").value("METHOD"))
			// Machine이 아니면 장비 파라미터는 버린다
			.andExpect(jsonPath("$.data.classification.equipmentParameters").isEmpty())
			.andExpect(jsonPath("$.data.classification.classifiedBy").value("USR-CMP-01"))
			.andExpect(jsonPath("$.data.classification.classifiedAt").exists());

		getAs(engineer, "/api/incidents/{id}/reports", incidentId)
			.andExpect(jsonPath("$.data.items[0].classification.category").value("METHOD"));
		getAs(engineer, "/api/incidents/{id}", incidentId)
			.andExpect(jsonPath("$.data.timeline[*].message", hasItem("보고서 v1 확정 · 원인 분류: Method (방법)")));
		String markdown = getAs(engineer, "/api/reports/{id}/export?format=markdown", reportId).andReturn()
			.getResponse().getContentAsString(StandardCharsets.UTF_8);
		assertThat(markdown).contains("> 원인 분류: Method (방법)").doesNotContain("DRAFT");
		// 확정본의 분류는 바뀌지 않는다
		postAs(engineer, "/api/reports/{id}/confirmations", confirmBody(1, "{\"category\":\"HOLD\"}"), reportId)
			.andExpect(status().isConflict()).andExpect(jsonPath("$.error.code").value("REPORT_CONFIRMED"));
	}

	@Test
	void holdIsConfirmedWithoutParametersAndLeavesIncidentStatus() throws Exception {
		Session engineer = cmp();
		String incidentId = newIncident(engineer, false).incidentId();
		String status = json(getAs(engineer, "/api/incidents/{id}", incidentId), "$.data.status");
		String reportId = draft(engineer, incidentId);

		postAs(engineer, "/api/reports/{id}/confirmations", confirmBody(1, "{\"category\":\"HOLD\"}"), reportId)
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.classification.category").value("HOLD"))
			.andExpect(jsonPath("$.data.classification.equipmentParameters").isEmpty());
		getAs(engineer, "/api/incidents/{id}", incidentId).andExpect(jsonPath("$.data.status").value(status));
		String markdown = getAs(engineer, "/api/reports/{id}/export", reportId).andReturn().getResponse()
			.getContentAsString(StandardCharsets.UTF_8);
		assertThat(markdown).contains("> 원인 분류: 보류 (원인 미확정)");
	}

	@Test
	void machineNeedsSensorsOfTheIncidentProcess() throws Exception {
		Session engineer = cmp();
		String incidentId = newIncident(engineer, false).incidentId();
		String reportId = draft(engineer, incidentId);

		postAs(engineer, "/api/reports/{id}/confirmations", confirmBody(1, "{\"category\":\"MACHINE\"}"), reportId)
			.andExpect(status().isUnprocessableEntity())
			.andExpect(jsonPath("$.error.code").value("EQUIPMENT_PARAMETER_REQUIRED"));
		postAs(engineer, "/api/reports/{id}/confirmations",
			confirmBody(1, "{\"category\":\"MACHINE\",\"equipmentParameters\":[\"gas_CF4\"]}"), reportId)
			.andExpect(status().isUnprocessableEntity())
			.andExpect(jsonPath("$.error.code").value("INVALID_EQUIPMENT_PARAMETER"))
			.andExpect(jsonPath("$.error.details.allowed", hasItem("head_pressure")));
		postAs(engineer, "/api/reports/{id}/confirmations",
			confirmBody(1, "{\"category\":\"MACHINE\",\"equipmentParameters\":[\"slurry_flow\",\"head_pressure\"]}"), reportId)
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.classification.category").value("MACHINE"))
			.andExpect(jsonPath("$.data.classification.equipmentParameters", contains("head_pressure", "slurry_flow")));
		String markdown = getAs(engineer, "/api/reports/{id}/export", reportId).andReturn().getResponse()
			.getContentAsString(StandardCharsets.UTF_8);
		assertThat(markdown).contains("> 원인 분류: Machine (설비) — head_pressure, slurry_flow");
	}

	@Test
	void approverChoosesClassificationWhenApprovingReportConfirmation() throws Exception {
		Session engineer = cmp();
		String incidentId = newIncident(engineer, false).incidentId();
		String reportId = draft(engineer, incidentId);
		String approvalId = json(postAs(engineer, "/api/incidents/{id}/proposals", """
			{"actionType":"CONFIRM_REPORT","payload":{"reportId":"%s","expectedContentRevision":1},"rationale":"검토 완료","expectedIncidentVersion":%d}
			""".formatted(reportId, incidentVersion(engineer, incidentId)), incidentId).andExpect(status().isCreated()),
			"$.data.approvalId");

		// 분류 없이는 승인할 수 없고, 승인 대기 상태로 남는다
		postAs(engineer, "/api/approvals/{id}/decisions", "{\"decision\":\"APPROVE\",\"expectedVersion\":1}", approvalId)
			.andExpect(status().isUnprocessableEntity())
			.andExpect(jsonPath("$.error.code").value("CAUSE_CLASSIFICATION_REQUIRED"));
		getAs(engineer, "/api/approvals/{id}", approvalId).andExpect(jsonPath("$.data.status").value("PENDING"))
			.andExpect(jsonPath("$.data.decisionInput").value(nullValue()));

		postAs(engineer, "/api/approvals/{id}/decisions", """
			{"decision":"APPROVE","expectedVersion":1,"classification":{"category":"MACHINE","equipmentParameters":["head_pressure"]}}
			""", approvalId)
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.executionRef.type").value("REPORT"))
			.andExpect(jsonPath("$.data.approval.decisionInput.category").value("MACHINE"))
			.andExpect(jsonPath("$.data.approval.decisionInput.equipmentParameters", contains("head_pressure")));
		getAs(engineer, "/api/reports/{id}", reportId)
			.andExpect(jsonPath("$.data.status").value("CONFIRMED"))
			.andExpect(jsonPath("$.data.classification.category").value("MACHINE"))
			.andExpect(jsonPath("$.data.classification.equipmentParameters", contains("head_pressure")))
			.andExpect(jsonPath("$.data.classification.classifiedBy").value("USR-CMP-01"));
	}

	@Test
	void rejectingReportConfirmationNeedsNoClassification() throws Exception {
		Session engineer = cmp();
		String incidentId = newIncident(engineer, false).incidentId();
		String reportId = draft(engineer, incidentId);
		String approvalId = json(postAs(engineer, "/api/incidents/{id}/proposals", """
			{"actionType":"CONFIRM_REPORT","payload":{"reportId":"%s","expectedContentRevision":1},"rationale":"검토 완료","expectedIncidentVersion":%d}
			""".formatted(reportId, incidentVersion(engineer, incidentId)), incidentId).andExpect(status().isCreated()),
			"$.data.approvalId");
		postAs(engineer, "/api/approvals/{id}/decisions",
			"{\"decision\":\"REJECT\",\"comment\":\"원인 확인 후 다시\",\"expectedVersion\":1}", approvalId)
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.approval.status").value("REJECTED"))
			.andExpect(jsonPath("$.data.approval.decisionInput").value(nullValue()));
		getAs(engineer, "/api/reports/{id}", reportId).andExpect(jsonPath("$.data.status").value("DRAFT"));
	}

	@Test
	void draftExportHasNoClassificationLine() throws Exception {
		Session engineer = cmp();
		String incidentId = newIncident(engineer, false).incidentId();
		String reportId = draft(engineer, incidentId);
		String markdown = getAs(engineer, "/api/reports/{id}/export", reportId).andReturn().getResponse()
			.getContentAsString(StandardCharsets.UTF_8);
		assertThat(markdown).startsWith("<!-- DRAFT").doesNotContain("원인 분류");
		getAs(engineer, "/api/incidents/{id}/reports", incidentId)
			.andExpect(jsonPath("$.data.items[*].classification", contains(nullValue())));
	}
}
