package com.fabcontext.api.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.UUID;

import com.fabcontext.api.support.ApiTestSupport;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("P10 조치 기록 · 효과 확인")
class ActionApiTest extends ApiTestSupport {

	private String actionBody(String id, Session s, String performedAt, boolean confirmed) throws Exception {
		return """
			{"actionType":"CHAMBER_SEASONING","description":"추가 시즈닝 5매","performedAt":"%s","evidenceIds":["ev:run:1"],
			 "confirmedByUser":%s,"expectedIncidentVersion":%d}""".formatted(performedAt, confirmed, incidentVersion(s, id));
	}

	@Test
	void recordAndVerifyWithoutClosingIncident() throws Exception {
		Session etch = cmp();
		String id = newIncident(etch, false).incidentId();
		var recorded = postAs(etch, "/api/incidents/{id}/actions", actionBody(id, etch, Instant.now().minusSeconds(60).toString(), true), id)
			.andExpect(status().isCreated())
			.andExpect(jsonPath("$.data.performedBy").value("USR-CMP-01"))
			.andExpect(jsonPath("$.data.source").value("USER"))
			.andExpect(jsonPath("$.data.verificationStatus").value("PENDING"))
			.andExpect(jsonPath("$.data.version").value(1));
		String actionId = json(recorded, "$.data.actionId");

		postAs(etch, "/api/actions/{id}/verifications", "{\"outcome\":\"INCONCLUSIVE\",\"rationale\":\"후속 run 부족\",\"confirmedByUser\":true,\"expectedVersion\":1}", actionId)
			.andExpect(status().isCreated()).andExpect(jsonPath("$.data.verificationStatus").value("INCONCLUSIVE"))
			.andExpect(jsonPath("$.data.version").value(2));
		postAs(etch, "/api/actions/{id}/verifications", "{\"outcome\":\"IMPROVED\",\"rationale\":\"후속 5 run 정상\",\"evidenceIds\":[\"ev:m\"],\"confirmedByUser\":true,\"expectedVersion\":2}", actionId)
			.andExpect(jsonPath("$.data.verificationStatus").value("IMPROVED"))
			.andExpect(jsonPath("$.data.verifications", hasSize(2)))
			.andExpect(jsonPath("$.data.verifications[1].verifiedBy").value("USR-CMP-01"));
		getAs(etch, "/api/incidents/{id}/actions", id).andExpect(jsonPath("$.data.items", hasSize(1)))
			.andExpect(jsonPath("$.data.items[0].verificationStatus").value("IMPROVED"));
		getAs(etch, "/api/incidents/{id}", id).andExpect(jsonPath("$.data.status").value("INVESTIGATING"))
			.andExpect(jsonPath("$.data.timeline[*].type", hasItems("ACTION_RECORDED", "ACTION_VERIFIED")));
	}

	@Test
	void recordValidation() throws Exception {
		Session etch = cmp();
		String id = newIncident(etch, false).incidentId();
		postAs(etch, "/api/incidents/{id}/actions", actionBody(id, etch, "2999-01-01T00:00:00Z", true), id)
			.andExpect(jsonPath("$.error.code").value("PERFORMED_AT_IN_FUTURE"));
		postAs(etch, "/api/incidents/{id}/actions", actionBody(id, etch, Instant.now().toString(), false), id)
			.andExpect(jsonPath("$.error.code").value("USER_CONFIRMATION_REQUIRED"));
		postAs(etch, "/api/incidents/{id}/actions", "{\"actionType\":\"X\",\"performedAt\":\"" + Instant.now() + "\",\"confirmedByUser\":true,\"expectedIncidentVersion\":1}", id)
			.andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.error.details.fields[0].field").value("description"));
		postAs(etch, "/api/incidents/{id}/actions", actionBody(id, etch, "not-a-time", true), id).andExpect(status().isBadRequest());
		postAs(etch, "/api/incidents/{id}/actions", actionBody(id, etch, Instant.now().toString(), true).replaceAll("\"expectedIncidentVersion\":\\d+", "\"expectedIncidentVersion\":0"), id)
			.andExpect(status().isConflict());
		postAs(cvd(), "/api/incidents/{id}/actions", actionBody(id, etch, Instant.now().toString(), true), id)
			.andExpect(status().isForbidden());
	}

	@Test
	void verificationValidation() throws Exception {
		Session etch = cmp();
		String id = newIncident(etch, false).incidentId();
		String actionId = json(postAs(etch, "/api/incidents/{id}/actions", actionBody(id, etch, Instant.now().toString(), true), id), "$.data.actionId");
		postAs(etch, "/api/actions/{id}/verifications", "{\"outcome\":\"PENDING\",\"rationale\":\"r\",\"confirmedByUser\":true,\"expectedVersion\":1}", actionId)
			.andExpect(jsonPath("$.error.code").value("INVALID_OUTCOME"));
		postAs(etch, "/api/actions/{id}/verifications", "{\"outcome\":\"IMPROVED\",\"rationale\":\"r\",\"confirmedByUser\":true,\"expectedVersion\":9}", actionId)
			.andExpect(status().isConflict());
		postAs(etch, "/api/actions/{id}/verifications", "{\"outcome\":\"IMPROVED\",\"rationale\":\"r\",\"confirmedByUser\":false,\"expectedVersion\":1}", actionId)
			.andExpect(status().isUnprocessableEntity());
		postAs(cvd(), "/api/actions/{id}/verifications", "{\"outcome\":\"IMPROVED\",\"rationale\":\"r\",\"confirmedByUser\":true,\"expectedVersion\":1}", actionId)
			.andExpect(status().isForbidden());
		postAs(etch, "/api/actions/{id}/verifications", "{\"outcome\":\"IMPROVED\",\"rationale\":\"r\",\"confirmedByUser\":true,\"expectedVersion\":1}", "act_missing")
			.andExpect(status().isNotFound());
	}

	@Test
	void idempotencyKeysAreScopedPerUser() throws Exception {
		Session etch = cmp();
		Session mgr = manager();
		String id = newIncident(etch, false).incidentId();
		String key = UUID.randomUUID().toString();
		String a1 = json(postWithKey(etch, key, "/api/incidents/{id}/actions", actionBody(id, etch, Instant.now().toString(), true), id)
			.andExpect(status().isCreated()), "$.data.actionId");
		String a2 = json(postWithKey(mgr, key, "/api/incidents/{id}/actions", actionBody(id, mgr, Instant.now().toString(), true), id)
			.andExpect(status().isCreated()), "$.data.actionId");
		assertThat(a1).isNotEqualTo(a2);
	}
}
