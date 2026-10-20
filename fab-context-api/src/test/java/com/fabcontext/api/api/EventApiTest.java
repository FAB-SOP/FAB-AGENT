package com.fabcontext.api.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.UUID;

import com.fabcontext.api.support.ApiTestSupport;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

@DisplayName("이벤트 접수 (시뮬레이터 서비스 토큰)")
class EventApiTest extends ApiTestSupport {

	private String event(String eventId) {
		return """
			{"eventId":"%s","source":"test","anomalyType":"PRESSURE_FLUCTUATION","severity":"LOW","processCode":"ETCH",
			 "equipmentId":"ETCH-PM2","detectedAt":"%s","summary":"Etch 챔버 압력 모니터 이탈"}"""
			.formatted(eventId, java.time.Instant.now().truncatedTo(java.time.temporal.ChronoUnit.SECONDS));
	}

	@Test
	void newEventCreatesIncidentAndQueuesBasicAnalysis() throws Exception {
		Session photo = login("etch.lee");
		var accepted = ingest(event("EVT-" + UUID.randomUUID())).andExpect(status().isAccepted())
			.andExpect(header().string("Location", startsWith("/api/incidents/inc_")))
			.andExpect(jsonPath("$.data.kind").value("BASIC"))
			.andExpect(jsonPath("$.data.duplicate").value(false))
			.andExpect(jsonPath("$.data.displayId").value(startsWith("EV-")));
		String id = json(accepted, "$.data.incidentId");
		getAs(photo, "/api/incidents/{id}", id)
			.andExpect(jsonPath("$.data.title").value("챔버 압력 변동"))
			.andExpect(jsonPath("$.data.severity").value("LOW"))
			.andExpect(jsonPath("$.data.timeline[0].type").value("DETECTED"));
		awaitJob(photo, json(accepted, "$.data.jobId"), "SUCCEEDED");
		// 웨이퍼 · run이 없는 사건: 변경 이력만 근거로 쓰고 데이터 한계를 밝힌다
		getAs(photo, "/api/incidents/{id}/analysis-results", id)
			.andExpect(jsonPath("$.data.observedFacts[*].evidenceIds[*]", hasItem("ev:change:DEMO-CHANGE-E02")))
			.andExpect(jsonPath("$.data.candidateCauses[0].status").value("INSUFFICIENT_EVIDENCE"))
			.andExpect(jsonPath("$.data.limitations", hasItem(containsString("웨이퍼가 없어"))));
	}

	@Test
	void duplicateEventReturnsExistingIncident() throws Exception {
		String eventId = "EVT-" + UUID.randomUUID();
		String id = json(ingest(event(eventId)).andExpect(status().isAccepted()), "$.data.incidentId");
		ingest(event(eventId)).andExpect(status().isOk())
			.andExpect(jsonPath("$.data.duplicate").value(true))
			.andExpect(jsonPath("$.data.incidentId").value(id));
	}

	@Test
	void serviceTokenIsRequiredAndSessionsAreNotEnough() throws Exception {
		mvc.perform(post("/api/events/anomalies").contentType(MediaType.APPLICATION_JSON).content(event("E1")))
			.andExpect(status().isUnauthorized());
		mvc.perform(post("/api/events/anomalies").header("X-Service-Token", "wrong")
				.contentType(MediaType.APPLICATION_JSON).content(event("E1")))
			.andExpect(status().isUnauthorized());
		mvc.perform(post("/api/events/anomalies").header("Authorization", "Bearer " + SERVICE_TOKEN)
				.contentType(MediaType.APPLICATION_JSON).content(event("EVT-" + UUID.randomUUID())))
			.andExpect(status().isAccepted());
		Session etch = cmp();
		mvc.perform(post("/api/events/anomalies").session(etch.http()).contentType(MediaType.APPLICATION_JSON)
				.content(event("E2"))).andExpect(status().isForbidden());
	}

	@Test
	void eventValidation() throws Exception {
		ingest("{\"eventId\":\"x\"}").andExpect(status().isUnprocessableEntity())
			.andExpect(jsonPath("$.error.details.fields[*].field", hasItems("anomalyType", "processCode", "equipmentId", "detectedAt")));
		ingest(event("EVT-bad").replace("LOW", "PANIC")).andExpect(status().isUnprocessableEntity());
		ingest(event("EVT-bad").replaceAll("\"detectedAt\":\"[^\"]+\"", "\"detectedAt\":\"yesterday\"")).andExpect(status().isBadRequest());
	}
}
