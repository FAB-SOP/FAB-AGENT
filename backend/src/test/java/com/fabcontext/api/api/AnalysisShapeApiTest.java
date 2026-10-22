package com.fabcontext.api.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.Map;

import com.fabcontext.api.contract.ResultShape;
import com.fabcontext.api.simulator.AnalysisFactory;
import com.fabcontext.api.support.ApiTestSupport;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;

/** 분석 결과 화면용 키 · relation 판단 · 승인 actionType 변환 (프론트 계약 ↔ 팀 계약) */
@DisplayName("분석 결과 모양 · actionType 변환")
class AnalysisShapeApiTest extends ApiTestSupport {

	@Autowired
	ResultShape shape;

	@Test
	void relationFollowsEvidenceProcessNotIdText() {
		// 예전 판단(근거 ID의 "-UPSTREAM-" 글자)은 새 근거 ID에서 늘 CURRENT였다 — EV-1009 1순위 가설
		Map<String, Object> up = shape.cause(1, "앞 공정 ETCH-PM2 제거량 변화의 기여", "CMP",
			List.of("E-RUN-RUN-W-CURRENT-009-3-ETCH", "E-CHG-ETCH-PM2", "E-PB-DEMO-ETCH_WALL"), List.of("E-RUN-RUN-W-CURRENT-009-3-CMP"),
			List.of("ETCH 담당자 확인"), List.of(), List.of());
		assertThat(up).containsEntry("processCode", "ETCH").containsEntry("relation", "UPSTREAM").containsEntry("rank", 1)
			.containsEntry("cause", "앞 공정 ETCH-PM2 제거량 변화의 기여").containsEntry("matrixCauseIds", List.of("ETCH_WALL"))
			.containsEntry("sourceIds", List.of("S08")).containsEntry("check", "ETCH 담당자 확인");
		assertThat((String) up.get("action")).contains("Etch 담당자");
		assertThat(up.get("verify")).isNotNull();
		assertThat(up.get("counterEvidence")).isEqualTo(List.of("E-RUN-RUN-W-CURRENT-009-3-CMP"));
		// EV-1006 · EV-1007 — 현재 공정
		Map<String, Object> cur = shape.cause(1, "CMP-PM3 헤드 압력", "CMP",
			List.of("E-RUN-RUN-W-CURRENT-006-3-CMP", "E-FEAT-RUN-W-CURRENT-006-3-CMP", "E-PB-DEMO-CMP_PRESSURE"), List.of(), List.of(),
			List.of(), List.of());
		assertThat(cur).containsEntry("processCode", "CMP").containsEntry("relation", "CURRENT");
	}

	@Test
	void resultsCarryScreenKeysDataVersionAndThread() throws Exception {
		Session cmp = cmp();
		String ev1009 = json(getAs(cmp, "/api/incidents?q=EV-1009"), "$.data.items[0].incidentId");
		// 시드에서 대기열에 둔 기본 분석을 시뮬레이터가 끝낼 때까지 기다린다
		for (int i = 0; i < 100; i++) {
			if (getAs(cmp, "/api/incidents/{id}/analysis-results", ev1009).andReturn().getResponse().getStatus() == 200) {
				break;
			}
			Thread.sleep(50);
		}
		getAs(cmp, "/api/incidents/{id}/analysis-results", ev1009).andExpect(status().isOk())
			.andExpect(jsonPath("$.data.dataVersion").value("causal-pack-v2.1-scenarios"))
			.andExpect(jsonPath("$.data.threadId").isNotEmpty())
			// 시뮬레이터 대역은 원인을 판정하지 않는다 — 「판정하지 않음」 자리 하나가 화면 키를 모두 갖는다(판정 확인은 fab-agent 테스트)
			.andExpect(jsonPath("$.data.limitations[0]").value(AnalysisFactory.NOT_ANALYSIS))
			.andExpect(jsonPath("$.data.candidateCauses", hasSize(1)))
			.andExpect(jsonPath("$.data.candidateCauses[0].relation").value("UNKNOWN"))
			.andExpect(jsonPath("$.data.candidateCauses[0].status").value("INSUFFICIENT_EVIDENCE"))
			.andExpect(jsonPath("$.data.candidateCauses[0].rank").value(1))
			.andExpect(jsonPath("$.data.candidateCauses[0].cause").isNotEmpty())
			.andExpect(jsonPath("$.data.candidateCauses[0].supportingEvidence").isArray())
			.andExpect(jsonPath("$.data.candidateCauses[0].counterEvidence").isArray())
			.andExpect(jsonPath("$.data.candidateCauses[0].sourceIds").isArray())
			.andExpect(jsonPath("$.data.observedFacts[0].label").isNotEmpty())
			.andExpect(jsonPath("$.data.observedFacts[0].unit").value("Å"))
			.andExpect(jsonPath("$.data.observedFacts[0].value").isNumber())
			.andExpect(jsonPath("$.data.observedFacts[0].evidenceId").isNotEmpty())
			// 옛 키도 남긴다(프론트가 어느 쪽을 읽는지 확정 전)
			.andExpect(jsonPath("$.data.candidateCauses[0].hypothesis").isNotEmpty())
			.andExpect(jsonPath("$.data.observedFacts[0].statement").isNotEmpty());
	}

	@Test
	void actionTypeIsFrontNameOnPublicApiAndTeamNameInternally() throws Exception {
		Session cmp = cmp();
		String id = newIncident(cmp, false).incidentId();
		String jobId = json(postAs(cmp, "/api/incidents/{id}/questions",
			"{\"question\":\"변환\",\"scope\":\"CURRENT\",\"expectedIncidentVersion\":" + incidentVersion(cmp, id) + "}", id), "$.data.jobId");
		awaitJob(cmp, jobId, "SUCCEEDED");
		String approvalId = json(mvc.perform(post("/internal/jobs/{id}/proposals", jobId).header("Authorization", "Bearer dev-service-token")
			.contentType(MediaType.APPLICATION_JSON).content("""
				{"actionType":"CREATE_MAINTENANCE_REQUEST","payload":{"ownerUserId":"USR-MNT-01","targetProcessCode":"CMP","question":"점검"},
				 "idempotencyKey":"conv-1"}""")).andExpect(jsonPath("$.data.actionType").value("CREATE_MAINTENANCE_REQUEST")),
			"$.data.approvalId");
		getAs(cmp, "/api/approvals/{id}", approvalId).andExpect(jsonPath("$.data.actionType").value("MAINTENANCE_REQUEST"));
		getAs(cmp, "/api/approvals?status=PENDING").andExpect(jsonPath("$.data.items[*].actionType", everyItem(
			is(in(List.of("CHECK_REQUEST", "MAINTENANCE_REQUEST", "RECORD_ACTION", "CONFIRM_REPORT"))))));
		mvc.perform(get("/internal/approvals/{id}", approvalId).header("Authorization", "Bearer dev-service-token"))
			.andExpect(jsonPath("$.data.actionType").value("CREATE_MAINTENANCE_REQUEST"));
		// 공개 API는 프론트 이름만 받는다
		postAs(cmp, "/api/incidents/{id}/proposals", "{\"actionType\":\"CREATE_CHECK_REQUEST\",\"payload\":{\"ownerUserId\":\"USR-ETCH-01\","
				+ "\"question\":\"q\"},\"rationale\":\"r\",\"expectedIncidentVersion\":" + incidentVersion(cmp, id) + "}", id)
			.andExpect(status().is4xxClientError());
		postAs(cmp, "/api/incidents/{id}/proposals", "{\"actionType\":\"CHECK_REQUEST\",\"payload\":{\"ownerUserId\":\"USR-ETCH-01\","
				+ "\"question\":\"q\"},\"rationale\":\"r\",\"expectedIncidentVersion\":" + incidentVersion(cmp, id) + "}", id)
			.andExpect(status().is2xxSuccessful()).andExpect(jsonPath("$.data.actionType").value("CHECK_REQUEST"));
	}
}
