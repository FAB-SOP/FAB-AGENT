package com.fabcontext.api.api;

import static org.hamcrest.Matchers.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fabcontext.api.support.ApiTestSupport;
import org.junit.jupiter.api.Test;

class InvestigationPermissionsApiTest extends ApiTestSupport {
	@Test
	void sessionsSeparateReadScopesAndInvestigationScopes() throws Exception {
		getAs(cmp(), "/api/auth/session").andExpect(jsonPath("$.data.investigationProcessScopes", contains("CMP")));
		getAs(login("mnt.jung"), "/api/auth/session")
			.andExpect(jsonPath("$.data.processScopes", containsInAnyOrder("CVD", "ETCH", "CMP")))
			.andExpect(jsonPath("$.data.investigationProcessScopes", hasSize(0)));
		getAs(manager(), "/api/auth/session")
			.andExpect(jsonPath("$.data.investigationProcessScopes", containsInAnyOrder("CVD", "ETCH", "CMP")));
	}

	@Test
	void maintenanceCanReadButCannotRunOrChangeAnotherTeamsInvestigation() throws Exception {
		Session owner = cmp();
		String id = newIncident(owner, false).incidentId();
		Session reader = login("mnt.jung");
		long version = incidentVersion(owner, id);
		getAs(reader, "/api/incidents/{id}", id).andExpect(status().isOk());
		postAs(reader, "/api/incidents/{id}/basic-analysis-jobs", "{\"expectedIncidentVersion\":" + version + "}", id)
			.andExpect(status().isForbidden()).andExpect(jsonPath("$.error.code").value("INVESTIGATION_WRITE_DENIED"));
		postAs(reader, "/api/incidents/{id}/questions", "{\"question\":\"다른 팀 재분석\",\"expectedIncidentVersion\":" + version + "}", id)
			.andExpect(status().isForbidden());
		String proposal = "{\"actionType\":\"CHECK_REQUEST\",\"payload\":{\"ownerUserId\":\"USR-CVD-01\",\"targetProcessCode\":\"CVD\",\"question\":\"확인 요청\"},\"rationale\":\"확인 필요\",\"expectedIncidentVersion\":" + version + "}";
		postAs(reader, "/api/incidents/{id}/proposals", proposal, id).andExpect(status().isForbidden());
		postAs(reader, "/api/incidents/{id}/actions", "{\"actionType\":\"REVIEW\",\"description\":\"검토\",\"performedAt\":\"2026-01-01T00:00:00Z\",\"confirmedByUser\":true,\"expectedIncidentVersion\":" + version + "}", id)
			.andExpect(status().isForbidden());
		postAs(reader, "/api/incidents/{id}/report-jobs", "{\"expectedIncidentVersion\":" + version + "}", id)
			.andExpect(status().isForbidden());
		getAs(owner, "/api/incidents/{id}", id).andExpect(jsonPath("$.data.version").value((int) version));
		String approval = json(postAs(owner, "/api/incidents/{id}/proposals", proposal, id).andExpect(status().isCreated()), "$.data.approvalId");
		postAs(reader, "/api/approvals/{id}/decisions", "{\"decision\":\"APPROVE\",\"expectedVersion\":1}", approval)
			.andExpect(status().isForbidden());
		getAs(owner, "/api/approvals/{id}", approval).andExpect(jsonPath("$.data.status").value("PENDING"));
	}

	@Test
	void foreignProcessIsDeniedEvenWhenCallingTheApiDirectly() throws Exception {
		Session owner = cmp();String id = newIncident(owner, false).incidentId();Session other = login("etch.lee");
		getAs(other, "/api/incidents/{id}", id).andExpect(status().isForbidden());
		postAs(other, "/api/incidents/{id}/basic-analysis-jobs", "{\"expectedIncidentVersion\":" + incidentVersion(owner,id) + "}", id)
			.andExpect(status().isForbidden()).andExpect(jsonPath("$.error.code").value("PROCESS_SCOPE_DENIED"));
	}
}
