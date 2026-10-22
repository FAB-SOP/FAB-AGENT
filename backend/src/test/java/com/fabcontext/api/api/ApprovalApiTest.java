package com.fabcontext.api.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import com.fabcontext.api.config.MutableClock;
import com.fabcontext.api.support.ApiTestSupport;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.ResultActions;

@DisplayName("P08 승인함 · 수동 제안 · 저장된 payload 실행")
class ApprovalApiTest extends ApiTestSupport {

	@Autowired
	MutableClock clock;

	@AfterEach
	void resetClock() {
		clock.reset();
	}

	private ResultActions propose(Session s, String incidentId, String actionType, String payload) throws Exception {
		return postAs(s, "/api/incidents/{id}/proposals", """
			{"actionType":"%s","payload":%s,"rationale":"근거 확인 필요","evidenceIds":["ev:x"],"expectedIncidentVersion":%d}
			""".formatted(actionType, payload, incidentVersion(s, incidentId)), incidentId);
	}

	private ResultActions decide(Session s, String approvalId, String decision, long version) throws Exception {
		return postAs(s, "/api/approvals/{id}/decisions",
			"{\"decision\":\"%s\",\"comment\":\"ok\",\"expectedVersion\":%d}".formatted(decision, version), approvalId);
	}

	@Test
	void manualProposalIsStoredPendingWithHashAndExpiry() throws Exception {
		Session etch = cmp();
		String id = newIncident(etch, false).incidentId();
		var created = propose(etch, id, "CHECK_REQUEST", "{\"ownerUserId\":\"USR-CVD-01\",\"question\":\"세정 이력 확인\"}")
			.andExpect(status().isCreated())
			.andExpect(header().string("Location", startsWith("/api/approvals/apv_")))
			.andExpect(jsonPath("$.data.status").value("PENDING"))
			.andExpect(jsonPath("$.data.source").value("USER"))
			.andExpect(jsonPath("$.data.jobId").doesNotExist())
			.andExpect(jsonPath("$.data.payload.targetProcessCode").value("CVD"))
			.andExpect(jsonPath("$.data.version").value(1));
		Instant created_at = Instant.parse(json(created, "$.data.createdAt"));
		Instant expires = Instant.parse(json(created, "$.data.expiresAt"));
		assertThat(Duration.between(created_at, expires)).isEqualTo(Duration.ofHours(24));
		getAs(etch, "/api/approvals?status=PENDING&incidentId=" + id).andExpect(jsonPath("$.data.items", hasSize(1)));
		getAs(etch, "/api/incidents/{id}", id).andExpect(jsonPath("$.data.timeline[-1].type").value("PROPOSAL_CREATED"));
	}

	@Test
	void sameContentGivesSameActionHash() throws Exception {
		Session etch = cmp();
		String id = newIncident(etch, false).incidentId();
		String payload = "{\"question\":\"Q\",\"ownerUserId\":\"USR-CVD-01\"}";
		String h1 = json(propose(etch, id, "CHECK_REQUEST", payload), "$.data.actionHash");
		String h2 = json(propose(etch, id, "CHECK_REQUEST", "{\"ownerUserId\":\"USR-CVD-01\",\"question\":\"Q\"}"), "$.data.actionHash");
		String h3 = json(propose(etch, id, "CHECK_REQUEST", "{\"ownerUserId\":\"USR-CVD-01\",\"question\":\"Q2\"}"), "$.data.actionHash");
		assertThat(h1).isEqualTo(h2).isNotEqualTo(h3);
	}

	@Test
	void payloadIsValidatedPerActionType() throws Exception {
		Session etch = cmp();
		String id = newIncident(etch, false).incidentId();
		propose(etch, id, "CHECK_REQUEST", "{\"question\":\"q\"}").andExpect(status().isUnprocessableEntity())
			.andExpect(jsonPath("$.error.code").value("INVALID_PAYLOAD"));
		propose(etch, id, "CHECK_REQUEST", "{\"ownerUserId\":\"u_nobody\",\"question\":\"q\"}")
			.andExpect(jsonPath("$.error.code").value("OWNER_NOT_FOUND"));
		propose(etch, id, "CHECK_REQUEST", "{\"ownerUserId\":\"USR-CVD-01\",\"question\":\"q\",\"dueAt\":\"tomorrow\"}")
			.andExpect(status().isUnprocessableEntity());
		propose(etch, id, "RECORD_ACTION", "{\"actionType\":\"X\",\"description\":\"d\",\"performedAt\":\"2999-01-01T00:00:00Z\"}")
			.andExpect(jsonPath("$.error.code").value("PERFORMED_AT_IN_FUTURE"));
		propose(etch, id, "CONFIRM_REPORT", "{\"reportId\":\"rpt_missing\",\"expectedContentRevision\":1}")
			.andExpect(status().isNotFound());
		propose(etch, id, "DELETE_EVERYTHING", "{}").andExpect(status().isBadRequest());
		postAs(etch, "/api/incidents/{id}/proposals", "{\"actionType\":\"CHECK_REQUEST\",\"payload\":{},\"rationale\":\"r\",\"jobId\":\"job_missing\",\"expectedIncidentVersion\":"
				+ incidentVersion(etch, id) + "}", id).andExpect(status().isNotFound());
		postAs(cvd(), "/api/incidents/{id}/proposals", "{\"actionType\":\"CHECK_REQUEST\",\"payload\":{},\"rationale\":\"r\",\"expectedIncidentVersion\":1}", id)
			.andExpect(status().isForbidden());
	}

	@Test
	void approvingRecordActionExecutesStoredPayloadOnly() throws Exception {
		Session etch = cmp();
		String id = newIncident(etch, false).incidentId();
		String performedAt = Instant.now().minusSeconds(600).toString();
		String approvalId = json(propose(etch, id, "RECORD_ACTION", """
			{"actionType":"CHAMBER_SEASONING","description":"추가 시즈닝 5매","performedAt":"%s"}""".formatted(performedAt)),
			"$.data.approvalId");
		getAs(etch, "/api/incidents/{id}/actions", id).andExpect(jsonPath("$.data.items", hasSize(0)));
		var result = decide(etch, approvalId, "APPROVE", 1).andExpect(status().isOk())
			.andExpect(jsonPath("$.data.approval.status").value("APPROVED"))
			.andExpect(jsonPath("$.data.approval.decidedBy").value("USR-CMP-01"))
			.andExpect(jsonPath("$.data.approval.version").value(2))
			.andExpect(jsonPath("$.data.executionRef.type").value("ACTION"))
			.andExpect(jsonPath("$.data.resumeJobId").doesNotExist());
		getAs(etch, "/api/incidents/{id}/actions", id)
			.andExpect(jsonPath("$.data.items[0].actionId").value(json(result, "$.data.executionRef.id").toString()))
			.andExpect(jsonPath("$.data.items[0].source").value("APPROVAL"))
			.andExpect(jsonPath("$.data.items[0].approvalId").value(approvalId))
			.andExpect(jsonPath("$.data.items[0].description").value("추가 시즈닝 5매"))
			.andExpect(jsonPath("$.data.items[0].evidenceIds", contains("ev:x")));
	}

	@Test
	void decisionGuardsVersionHashStateAndScope() throws Exception {
		Session etch = cmp();
		String id = newIncident(etch, false).incidentId();
		String approvalId = json(propose(etch, id, "CHECK_REQUEST", "{\"ownerUserId\":\"USR-CVD-01\",\"question\":\"q\"}"),
			"$.data.approvalId");
		decide(etch, approvalId, "APPROVE", 2).andExpect(status().isConflict())
			.andExpect(jsonPath("$.error.code").value("VERSION_CONFLICT"));
		postAs(etch, "/api/approvals/{id}/decisions", "{\"decision\":\"APPROVE\",\"expectedVersion\":1,\"actionHash\":\"sha256:x\"}",
				approvalId).andExpect(jsonPath("$.error.code").value("ACTION_HASH_MISMATCH"));
		postAs(etch, "/api/approvals/{id}/decisions", "{\"decision\":\"MAYBE\",\"expectedVersion\":1}", approvalId)
			.andExpect(status().isUnprocessableEntity());
		getAs(cvd(), "/api/approvals/{id}", approvalId).andExpect(status().isForbidden());
		decide(cvd(), approvalId, "APPROVE", 1).andExpect(status().isForbidden());

		String key = UUID.randomUUID().toString();
		String body = "{\"decision\":\"REJECT\",\"comment\":\"대상 재검토\",\"expectedVersion\":1}";
		postWithKey(etch, key, "/api/approvals/{id}/decisions", body, approvalId).andExpect(status().isOk())
			.andExpect(jsonPath("$.data.approval.status").value("REJECTED"))
			.andExpect(jsonPath("$.data.approval.decisionComment").value("대상 재검토"));
		postWithKey(etch, key, "/api/approvals/{id}/decisions", body, approvalId).andExpect(status().isOk())
			.andExpect(header().string("Idempotent-Replayed", "true"));
		decide(etch, approvalId, "APPROVE", 2).andExpect(status().isConflict())
			.andExpect(jsonPath("$.error.code").value("APPROVAL_NOT_PENDING"));
		getAs(etch, "/api/approvals/{id}", "apv_missing").andExpect(status().isNotFound());
	}

	@Test
	void expiredApprovalCannotBeExecuted() throws Exception {
		Session etch = cmp();
		String id = newIncident(etch, false).incidentId();
		String approvalId = json(propose(etch, id, "CHECK_REQUEST", "{\"ownerUserId\":\"USR-CVD-01\",\"question\":\"q\"}"),
			"$.data.approvalId");
		clock.travelTo(Instant.now().plus(Duration.ofHours(25)));
		decide(etch, approvalId, "APPROVE", 1).andExpect(status().isConflict())
			.andExpect(jsonPath("$.error.code").value("APPROVAL_EXPIRED"))
			.andExpect(jsonPath("$.error.details.status").value("EXPIRED"));
		getAs(etch, "/api/approvals/{id}", approvalId).andExpect(jsonPath("$.data.status").value("EXPIRED"))
			.andExpect(jsonPath("$.data.version").value(2));
		getAs(etch, "/api/approvals?status=EXPIRED&incidentId=" + id).andExpect(jsonPath("$.data.items", hasSize(1)));
	}

	@Test
	void listIsScopedAndPaged() throws Exception {
		getAs(cvd(), "/api/approvals?status=PENDING")
			.andExpect(jsonPath("$.data.items[*].processCode", everyItem(is("CVD"))))
			.andExpect(jsonPath("$.data.items", not(empty())));
		getAs(cmp(), "/api/approvals?size=1").andExpect(jsonPath("$.data.items", hasSize(lessThanOrEqualTo(1))))
			.andExpect(jsonPath("$.data.items[*].processCode", everyItem(is("CMP"))));
	}
}
