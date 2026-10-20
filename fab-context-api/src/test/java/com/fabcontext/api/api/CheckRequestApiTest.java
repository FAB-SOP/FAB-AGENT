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
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("P09 확인 요청함 · 회신")
class CheckRequestApiTest extends ApiTestSupport {

	/** 수동 제안 → 승인으로 OPEN 요청을 만든다 (연결된 Agent 대기 없음). */
	private String[] openManualRequest(Session etch) throws Exception {
		String id = newIncident(etch, false).incidentId();
		String approvalId = json(postAs(etch, "/api/incidents/{id}/proposals", """
			{"actionType":"CHECK_REQUEST","payload":{"ownerUserId":"USR-CVD-01","question":"세정 주기 영향 확인","dueAt":"2030-01-01T00:00:00Z"},
			 "rationale":"r","evidenceIds":["ev:change:DEMO-CHANGE-E02"],"expectedIncidentVersion":%d}
			""".formatted(incidentVersion(etch, id)), id), "$.data.approvalId");
		String requestId = json(postAs(etch, "/api/approvals/{id}/decisions", "{\"decision\":\"APPROVE\",\"expectedVersion\":1}",
				approvalId), "$.data.executionRef.id");
		return new String[] {id, requestId};
	}

	@Test
	void manualRequestReplyDoesNotCreateResumeJob() throws Exception {
		Session etch = cmp();
		Session photo = cvd();
		String[] ids = openManualRequest(etch);
		getAs(etch, "/api/incidents/{id}", ids[0]).andExpect(jsonPath("$.data.status").value("WAITING_REPLY"));
		getAs(photo, "/api/check-requests?box=received&status=OPEN&incidentId=" + ids[0])
			.andExpect(jsonPath("$.data.items", hasSize(1)))
			.andExpect(jsonPath("$.data.items[0].incidentDisplayId").value(startsWith("EV-")))
			.andExpect(jsonPath("$.data.items[0].requesterUserId").value("USR-CMP-01"));
		getAs(etch, "/api/check-requests?box=sent&incidentId=" + ids[0]).andExpect(jsonPath("$.data.items", hasSize(1)));
		getAs(photo, "/api/check-requests/{id}", ids[1])
			.andExpect(jsonPath("$.data.question").value("세정 주기 영향 확인"))
			.andExpect(jsonPath("$.data.evidenceIds", contains("ev:change:DEMO-CHANGE-E02")))
			.andExpect(jsonPath("$.data.dueAt").value("2030-01-01T00:00:00Z"))
			.andExpect(jsonPath("$.data.waitingJobId").doesNotExist())
			.andExpect(jsonPath("$.data.incident.equipmentId").value("CMP-PM3"))
			.andExpect(jsonPath("$.data.replies", hasSize(0)));

		postAs(photo, "/api/check-requests/{id}/replies", "{\"findings\":\"영향 없음\",\"evidenceIds\":[\"ev:photo:1\"],\"confirmedByUser\":true,\"expectedVersion\":1}", ids[1])
			.andExpect(status().isCreated())
			.andExpect(header().string("Location", "/api/check-requests/" + ids[1]))
			.andExpect(jsonPath("$.data.authorUserId").value("USR-CVD-01"))
			.andExpect(jsonPath("$.data.resumeJobId").doesNotExist())
			.andExpect(jsonPath("$.data.requestStatus").value("REPLIED"))
			.andExpect(jsonPath("$.data.requestVersion").value(2));
		getAs(etch, "/api/check-requests/{id}", ids[1]).andExpect(jsonPath("$.data.replies[0].findings").value("영향 없음"))
			.andExpect(jsonPath("$.data.repliedAt").exists());
		getAs(etch, "/api/incidents/{id}", ids[0]).andExpect(jsonPath("$.data.status").value("INVESTIGATING"))
			.andExpect(jsonPath("$.data.timeline[-1].type").value("REPLY_RECEIVED"));
	}

	@Test
	void replyRules() throws Exception {
		Session etch = cmp();
		Session photo = cvd();
		String requestId = openManualRequest(etch)[1];
		String ok = "{\"findings\":\"f\",\"confirmedByUser\":true,\"expectedVersion\":1}";
		postAs(etch, "/api/check-requests/{id}/replies", ok, requestId).andExpect(status().isForbidden())
			.andExpect(jsonPath("$.error.code").value("NOT_REQUEST_OWNER"));
		postAs(photo, "/api/check-requests/{id}/replies", "{\"findings\":\"f\",\"confirmedByUser\":false,\"expectedVersion\":1}", requestId)
			.andExpect(jsonPath("$.error.code").value("USER_CONFIRMATION_REQUIRED"));
		postAs(photo, "/api/check-requests/{id}/replies", "{\"findings\":\"\",\"confirmedByUser\":true,\"expectedVersion\":1}", requestId)
			.andExpect(status().isUnprocessableEntity());
		postAs(photo, "/api/check-requests/{id}/replies", "{\"findings\":\"f\",\"confirmedByUser\":true,\"expectedVersion\":3}", requestId)
			.andExpect(status().isConflict()).andExpect(jsonPath("$.error.code").value("VERSION_CONFLICT"));
		postAs(photo, "/api/check-requests/{id}/replies", ok, requestId).andExpect(status().isCreated());
		postAs(photo, "/api/check-requests/{id}/replies", "{\"findings\":\"f\",\"confirmedByUser\":true,\"expectedVersion\":2}", requestId)
			.andExpect(status().isConflict()).andExpect(jsonPath("$.error.code").value("REQUEST_NOT_OPEN"));
	}

	@Test
	void visibilityAndListValidation() throws Exception {
		Session etch = cmp();
		String requestId = openManualRequest(etch)[1];
		getAs(manager(), "/api/check-requests/{id}", requestId).andExpect(status().isOk());
		getAs(etch, "/api/check-requests/{id}", "chk_missing").andExpect(status().isNotFound());
		getAs(etch, "/api/check-requests?box=archive").andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.error.code").value("INVALID_BOX"));
		getAs(etch, "/api/check-requests").andExpect(jsonPath("$.data.items[*].ownerUserId", everyItem(is("USR-CMP-01"))));
	}
}
