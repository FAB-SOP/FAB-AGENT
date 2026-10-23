package com.fabcontext.api.api;

import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fabcontext.api.support.ApiTestSupport;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("003 확정 전 남은 일 — 차단 목록 · 요청 철회 · 업무 미수행 닫기 · 승인 경로")
class ReportConfirmBlockersApiTest extends ApiTestSupport {

	private String draft(Session s, String incidentId) throws Exception {
		String jobId = json(postAs(s, "/api/incidents/{id}/report-jobs",
			"{\"expectedIncidentVersion\":" + incidentVersion(s, incidentId) + "}", incidentId)
			.andExpect(status().isAccepted()), "$.data.jobId");
		awaitJob(s, jobId, "SUCCEEDED");
		return json(getAs(s, "/api/jobs/{id}", jobId), "$.data.resultRef.id");
	}

	private static String confirmBody(long revision) {
		return "{\"expectedContentRevision\":" + revision + ",\"reviewConfirmed\":true,\"classification\":{\"category\":\"HOLD\"}}";
	}

	/** 수동 제안 → 승인으로 CVD 담당자에게 OPEN 확인 요청을 만든다 */
	private String openRequest(Session engineer, String incidentId) throws Exception {
		String approvalId = json(postAs(engineer, "/api/incidents/{id}/proposals", """
			{"actionType":"CHECK_REQUEST","payload":{"ownerUserId":"USR-CVD-01","question":"세정 주기 영향 확인","dueAt":"2030-01-01T00:00:00Z"},
			 "rationale":"r","evidenceIds":["ev:change:DEMO-CHANGE-E02"],"expectedIncidentVersion":%d}
			""".formatted(incidentVersion(engineer, incidentId)), incidentId), "$.data.approvalId");
		return json(postAs(engineer, "/api/approvals/{id}/decisions", "{\"decision\":\"APPROVE\",\"expectedVersion\":1}",
			approvalId), "$.data.executionRef.id");
	}

	/** 직접 확인 업무를 제안 · 채택해 READY로 만든다 */
	private String readyWork(Session engineer, String incidentId) throws Exception {
		String result = json(getAs(engineer, "/api/incidents/{id}/analysis-results", incidentId), "$.data.resultId");
		String taskId = json(postAs(engineer, "/api/incidents/{id}/work-items", """
			{"operationCode":"REVIEW_SENSOR_DATA","title":"유량 실측값 비교","reason":"후속 확인","sourceResultId":"%s","expectedIncidentVersion":%d}
			""".formatted(result, incidentVersion(engineer, incidentId)), incidentId).andExpect(status().isCreated()), "$.data.taskId");
		postAs(engineer, "/api/work-items/{id}/adoptions", "{\"expectedVersion\":1}", taskId)
			.andExpect(jsonPath("$.data.status").value("READY"));
		return taskId;
	}

	@Test
	void unansweredRequestBlocksUntilTheRequesterWithdrawsIt() throws Exception {
		Session engineer = cmp();
		String incidentId = newIncident(engineer, false).incidentId();
		String requestId = openRequest(engineer, incidentId);
		String reportId = draft(engineer, incidentId);

		postAs(engineer, "/api/reports/{id}/confirmations", confirmBody(1), reportId)
			.andExpect(status().isConflict())
			.andExpect(jsonPath("$.error.code").value("CONFIRMATION_BLOCKED"))
			.andExpect(jsonPath("$.error.details.blockers", hasSize(1)))
			.andExpect(jsonPath("$.error.details.blockers[0].kind").value("REQUEST_UNANSWERED"))
			.andExpect(jsonPath("$.error.details.blockers[0].id").value(requestId))
			.andExpect(jsonPath("$.error.details.blockers[0].label").exists());
		getAs(engineer, "/api/reports/{id}", reportId).andExpect(jsonPath("$.data.status").value("DRAFT"));

		// 받는 사람은 철회할 수 없다(다른 공정) · 사유가 필요하다 · 판본을 맞춰야 한다
		postAs(cvd(), "/api/check-requests/{id}/withdrawals", "{\"reason\":\"필요 없어짐\",\"expectedVersion\":1}", requestId)
			.andExpect(status().isForbidden());
		postAs(engineer, "/api/check-requests/{id}/withdrawals", "{\"reason\":\" \",\"expectedVersion\":1}", requestId)
			.andExpect(status().is4xxClientError());
		postAs(engineer, "/api/check-requests/{id}/withdrawals", "{\"reason\":\"필요 없어짐\",\"expectedVersion\":9}", requestId)
			.andExpect(status().isConflict());
		postAs(engineer, "/api/check-requests/{id}/withdrawals", "{\"reason\":\"다른 경로로 확인함\",\"expectedVersion\":1}", requestId)
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.status").value("CANCELLED"))
			.andExpect(jsonPath("$.data.withdrawnBy").value("USR-CMP-01"))
			.andExpect(jsonPath("$.data.withdrawnAt").exists())
			.andExpect(jsonPath("$.data.withdrawReason").value("다른 경로로 확인함"));
		// 받는 사람에게도 철회 상태가 보인다
		getAs(cvd(), "/api/check-requests/{id}", requestId).andExpect(jsonPath("$.data.status").value("CANCELLED"));
		getAs(engineer, "/api/incidents/{id}", incidentId)
			.andExpect(jsonPath("$.data.timeline[*].type", hasItem("REQUEST_WITHDRAWN")));
		postAs(engineer, "/api/check-requests/{id}/withdrawals", "{\"reason\":\"다시\",\"expectedVersion\":2}", requestId)
			.andExpect(status().isConflict()).andExpect(jsonPath("$.error.code").value("CHECK_REQUEST_NOT_WITHDRAWABLE"));

		postAs(engineer, "/api/reports/{id}/confirmations", confirmBody(1), reportId).andExpect(status().isOk());
		getAs(engineer, "/api/incidents/{id}", incidentId).andExpect(jsonPath("$.data.status").value("CLOSED"));
	}

	@Test
	void adoptedWorkBlocksUntilCompletedOrClosedAsUnperformed() throws Exception {
		Session engineer = cmp();
		String incidentId = newIncident(engineer, false).incidentId();
		String taskId = readyWork(engineer, incidentId);
		String reportId = draft(engineer, incidentId);

		postAs(engineer, "/api/reports/{id}/confirmations", confirmBody(1), reportId)
			.andExpect(status().isConflict())
			.andExpect(jsonPath("$.error.details.blockers[*].kind", contains("WORK_UNPERFORMED")))
			.andExpect(jsonPath("$.error.details.blockers[0].id").value(taskId));

		long version = number(getAs(engineer, "/api/incidents/{id}/work-items", incidentId), "$.data.items[0].version");
		postAs(engineer, "/api/work-items/{id}/unperformed-closures", "{\"reason\":\"\",\"expectedVersion\":" + version + "}", taskId)
			.andExpect(status().is4xxClientError());
		postAs(engineer, "/api/work-items/{id}/unperformed-closures",
				"{\"reason\":\"설비 반출로 수행 불가\",\"expectedVersion\":" + version + "}", taskId)
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.status").value("CLOSED_UNPERFORMED"))
			.andExpect(jsonPath("$.data.closedBy").value("USR-CMP-01"))
			.andExpect(jsonPath("$.data.closeReason").value("설비 반출로 수행 불가"));
		postAs(engineer, "/api/work-items/{id}/unperformed-closures",
				"{\"reason\":\"다시\",\"expectedVersion\":" + (version + 1) + "}", taskId)
			.andExpect(status().isConflict()).andExpect(jsonPath("$.error.code").value("WORK_NOT_CLOSABLE"));
		getAs(engineer, "/api/incidents/{id}", incidentId)
			.andExpect(jsonPath("$.data.timeline[*].type", hasItem("WORK_CLOSED_UNPERFORMED")));

		postAs(engineer, "/api/reports/{id}/confirmations", confirmBody(1), reportId).andExpect(status().isOk());
		// 종료 사건에는 더 이상 정리 요청도 받지 않는다
		postAs(engineer, "/api/work-items/{id}/unperformed-closures",
				"{\"reason\":\"다시\",\"expectedVersion\":" + (version + 1) + "}", taskId)
			.andExpect(status().isConflict()).andExpect(jsonPath("$.error.code").value("INCIDENT_CLOSED"));
	}

	@Test
	void proposedWorkCannotBeClosedAsUnperformedAndDoesNotBlock() throws Exception {
		Session engineer = cmp();
		String incidentId = newIncident(engineer, false).incidentId();
		String result = json(getAs(engineer, "/api/incidents/{id}/analysis-results", incidentId), "$.data.resultId");
		String taskId = json(postAs(engineer, "/api/incidents/{id}/work-items", """
			{"operationCode":"REVIEW_SENSOR_DATA","title":"제안만","reason":"후보","sourceResultId":"%s","expectedIncidentVersion":%d}
			""".formatted(result, incidentVersion(engineer, incidentId)), incidentId), "$.data.taskId");
		postAs(engineer, "/api/work-items/{id}/unperformed-closures", "{\"reason\":\"안 함\",\"expectedVersion\":1}", taskId)
			.andExpect(status().isConflict()).andExpect(jsonPath("$.error.code").value("WORK_NOT_CLOSABLE"));
		String reportId = draft(engineer, incidentId);
		postAs(engineer, "/api/reports/{id}/confirmations", confirmBody(1), reportId).andExpect(status().isOk());
	}

	@Test
	void pendingProposalBlocksAndApprovedConfirmationIsNotBlockedByItself() throws Exception {
		Session engineer = cmp();
		String incidentId = newIncident(engineer, false).incidentId();
		String reportId = draft(engineer, incidentId);
		String confirmApproval = json(postAs(engineer, "/api/incidents/{id}/proposals", """
			{"actionType":"CONFIRM_REPORT","payload":{"reportId":"%s","expectedContentRevision":1},"rationale":"검토 완료","expectedIncidentVersion":%d}
			""".formatted(reportId, incidentVersion(engineer, incidentId)), incidentId).andExpect(status().isCreated()),
			"$.data.approvalId");

		// 직접 확정은 승인 대기 중인 제안(이 확정 제안)에 막힌다
		postAs(engineer, "/api/reports/{id}/confirmations", confirmBody(1), reportId)
			.andExpect(status().isConflict())
			.andExpect(jsonPath("$.error.details.blockers[0].kind").value("APPROVAL_PENDING"))
			.andExpect(jsonPath("$.error.details.blockers[0].id").value(confirmApproval));
		// 그 제안을 승인하면 자기 자신은 남은 일로 세지 않고 확정 · 종료된다
		postAs(engineer, "/api/approvals/{id}/decisions",
				"{\"decision\":\"APPROVE\",\"expectedVersion\":1,\"classification\":{\"category\":\"HOLD\"}}", confirmApproval)
			.andExpect(status().isOk()).andExpect(jsonPath("$.data.executionRef.type").value("REPORT"));
		getAs(engineer, "/api/incidents/{id}", incidentId).andExpect(jsonPath("$.data.status").value("CLOSED"));
	}
}
