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
import org.springframework.test.context.TestPropertySource;

/**
 * Agent 지연을 길게 둬서 '진행 중' 상태의 충돌 규칙을 결정적으로 검증한다.
 * 설정이 달라 별도 컨텍스트가 뜨므로, 같은 Postgres 컨테이너 안에서 자기 스키마(backend_slow)에 따로 시드한다.
 */
@DisplayName("진행 중 작업 충돌 (Agent 지연 1.5초)")
@TestPropertySource(properties = {"fab.agent.step-delay-ms=1500",
		"spring.jpa.properties.hibernate.default_schema=backend_slow", "spring.datasource.hikari.schema=backend_slow"})
class SlowAgentApiTest extends ApiTestSupport {

	@Test
	void secondJobWhileOneIsInProgressIsRejected() throws Exception {
		Session etch = cmp();
		var ingested = ingest("""
			{"eventId":"EVT-SLOW-%s","anomalyType":"THICKNESS_OOC_R1","processCode":"CMP","equipmentId":"CMP-PM3",
			 "waferIds":["W-CURRENT-007-3"],"runIds":["RUN-W-CURRENT-007-3-CMP"],"detectedAt":"2026-10-20T00:00:00Z"}
			""".formatted(System.nanoTime())).andExpect(status().isAccepted());
		String id = json(ingested, "$.data.incidentId");
		long v = incidentVersion(etch, id);
		getAs(etch, "/api/incidents/{id}", id).andExpect(jsonPath("$.data.status").value("OPEN"))
			.andExpect(jsonPath("$.data.activeJob.status").value("QUEUED"));
		postAs(etch, "/api/incidents/{id}/questions", "{\"question\":\"q\",\"expectedIncidentVersion\":" + v + "}", id)
			.andExpect(status().isConflict()).andExpect(jsonPath("$.error.code").value("JOB_IN_PROGRESS"));
		postAs(etch, "/api/incidents/{id}/basic-analysis-jobs", "{\"expectedIncidentVersion\":" + v + "}", id)
			.andExpect(status().isConflict()).andExpect(jsonPath("$.error.code").value("JOB_IN_PROGRESS"));

		postAs(etch, "/api/incidents/{id}/report-jobs", "{\"expectedIncidentVersion\":" + v + "}", id)
			.andExpect(status().isAccepted());
		v = incidentVersion(etch, id);
		postAs(etch, "/api/incidents/{id}/report-jobs", "{\"expectedIncidentVersion\":" + v + "}", id)
			.andExpect(status().isConflict()).andExpect(jsonPath("$.error.code").value("REPORT_JOB_IN_PROGRESS"));
		getAs(etch, "/api/dashboard?period=24h").andExpect(jsonPath("$.data.counts.analyzing").value(greaterThanOrEqualTo(1)));
	}
}
