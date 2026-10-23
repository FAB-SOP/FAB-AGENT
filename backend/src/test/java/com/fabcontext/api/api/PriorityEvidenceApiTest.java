package com.fabcontext.api.api;

import com.fabcontext.api.support.ApiTestSupport;
import com.fabcontext.api.domain.processdata.ProcessDataRepositories.RouteRunRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import static org.hamcrest.Matchers.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class PriorityEvidenceApiTest extends ApiTestSupport {
	@Autowired RouteRunRepository routes;
	private static final String PATH="/internal/runs/{id}/priority-evidence?policyVersion=DEMO-1.2-DRAFT";
	private static final String NORMAL="RUN-W-CURRENT-010-3-CMP";
	@Test void outOfControlMetrologyIsHighWithEvidenceAndPolicyVersion() throws Exception {
		mvc.perform(get(PATH,"RUN-W-CURRENT-009-3-CMP").header("Authorization","Bearer dev-service-token"))
			.andExpect(status().isOk()).andExpect(jsonPath("$.data.data.assessment.level").value("HIGH"))
			.andExpect(jsonPath("$.data.data.assessment.ruleIds",hasItem("P-HIGH-01")))
			.andExpect(jsonPath("$.data.data.assessment.evidenceIds",not(empty())))
			.andExpect(jsonPath("$.data.data.policy.gradeAssignedByTool").value(true))
			.andExpect(jsonPath("$.data.data.policy.enabled").value(true))
			.andExpect(jsonPath("$.data.data.assessment.mode").value("DEMO_ACTIVE"))
			.andExpect(jsonPath("$.data.synthetic").value(true));
	}
	@Test void metrologyNotYetPublishedIsProvisionalMediumAndDoesNotLeakValues() throws Exception {
		var run=routes.findByRunId(NORMAL).orElseThrow();
		mvc.perform(get(PATH+"&asOf={asOf}",NORMAL,run.getEndedAt().plusSeconds(1).toString()).header("Authorization","Bearer dev-service-token"))
			.andExpect(status().isOk()).andExpect(jsonPath("$.data.data.assessment.level").value("MEDIUM"))
			.andExpect(jsonPath("$.data.data.assessment.provisional").value(true))
			.andExpect(jsonPath("$.data.data.metrology[2].status").value("NOT_AVAILABLE_AS_OF"))
			.andExpect(jsonPath("$.data.data.metrology[2].meanA").doesNotExist());
	}
	@Test void serviceTokenRequiredAndOrdinaryLoginIsInsufficient() throws Exception {
		mvc.perform(get(PATH,NORMAL)).andExpect(status().isUnauthorized());
		mvc.perform(get(PATH,NORMAL).session(cmp().http())).andExpect(status().isForbidden());
	}
	@Test void unknownRunWrongPolicyAndFutureTimeReturnExplicitErrors() throws Exception {
		mvc.perform(get(PATH,"missing").header("Authorization","Bearer dev-service-token")).andExpect(status().isNotFound());
		mvc.perform(get("/internal/runs/{id}/priority-evidence?policyVersion=wrong",NORMAL).header("Authorization","Bearer dev-service-token")).andExpect(status().isBadRequest());
		mvc.perform(get(PATH+"&asOf=2030-01-01T00:00:00Z",NORMAL).header("Authorization","Bearer dev-service-token")).andExpect(status().isForbidden());
	}
	@Test void compactResponseFitsCurrentMcpLimitAndDetailedViewPreservesIntervals() throws Exception {
		for(String run:java.util.List.of("RUN-W-CURRENT-006-3-CMP","RUN-W-CURRENT-007-3-CMP","RUN-W-CURRENT-009-3-CMP",NORMAL)) {
			var response=mvc.perform(get(PATH,run).header("Authorization","Bearer dev-service-token"))
				.andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
			org.assertj.core.api.Assertions.assertThat(response.length()).as("MCP response size for %s",run).isLessThanOrEqualTo(6000);
		}
		mvc.perform(get(PATH+"&includeDetails=true","RUN-W-CURRENT-007-3-CMP").header("Authorization","Bearer dev-service-token"))
			.andExpect(status().isOk()).andExpect(jsonPath("$.data.data.sensorObservations[0].segments").isArray());
	}
}
