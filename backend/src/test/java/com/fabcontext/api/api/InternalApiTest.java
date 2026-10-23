package com.fabcontext.api.api;

import static org.hamcrest.Matchers.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;

import com.fabcontext.api.domain.job.Job;
import com.fabcontext.api.domain.job.JobKind;
import com.fabcontext.api.domain.job.JobRepository;
import com.fabcontext.api.support.ApiTestSupport;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.ResultActions;

/** 내부 API(/internal/**) — fab-agent 업무 MCP · Worker가 부르는 경로. 응답 모양은 fab-agent backend 대역과 같다. */
@DisplayName("내부 API (업무 MCP · Agent Worker)")
class InternalApiTest extends ApiTestSupport {

	static final String BEARER = "Bearer dev-service-token";

	@Autowired
	JobRepository jobs;

	@Autowired
	JdbcTemplate jdbc;

	private ResultActions iget(String url, Object... vars) throws Exception {
		return mvc.perform(get(url, vars).header("Authorization", BEARER));
	}

	private ResultActions ipost(String url, String body, Object... vars) throws Exception {
		return mvc.perform(post(url, vars).header("Authorization", BEARER).contentType(MediaType.APPLICATION_JSON)
			.content(body));
	}

	@Test
	void serviceTokenIsRequiredAndSessionIsNotEnough() throws Exception {
		mvc.perform(get("/internal/incidents/EV-1009")).andExpect(status().isUnauthorized());
		mvc.perform(get("/internal/incidents/EV-1009").header("Authorization", "Bearer wrong"))
			.andExpect(status().isUnauthorized());
		mvc.perform(get("/internal/incidents/EV-1009").session(cmp().http())).andExpect(status().isForbidden());
		iget("/internal/incidents/EV-1009").andExpect(status().isOk());
	}

	@Test
	void packDataToolsReturnServerSideCalculation() throws Exception {
		iget("/internal/incidents/EV-1009")
			.andExpect(jsonPath("$.data.data.displayId").value("EV-1009"))
			.andExpect(jsonPath("$.data.data.equipmentId").value("CMP-PM3"))
			.andExpect(jsonPath("$.data.data.waferIds", contains("W-CURRENT-009-3")))
			.andExpect(jsonPath("$.data.data.metrics.rule").value("R1_ONLY"))
			.andExpect(jsonPath("$.data.evidenceRefs[0].evidenceId").value("E-INC-EV-1009"))
			.andExpect(jsonPath("$.data.synthetic").value(true));
		iget("/internal/wafers/W-CURRENT-009-3/lineage")
			.andExpect(jsonPath("$.data.data.path").value("CVD:CVD-PM1 → ETCH:ETCH-PM2 → CMP:CMP-PM3"));
		iget("/internal/runs/RUN-W-CURRENT-009-3-ETCH")
			.andExpect(jsonPath("$.data.data.amountKind").value("REMOVED"))
			.andExpect(jsonPath("$.data.data.amountA").value(4896.3))
			.andExpect(jsonPath("$.data.data.amountWithinNormalVariation").value(false))
			.andExpect(jsonPath("$.data.data.baselineAmount.samePm.n").value(100))
			.andExpect(jsonPath("$.data.evidenceRefs[0].evidenceId").value("E-RUN-RUN-W-CURRENT-009-3-ETCH"));
		iget("/internal/runs/RUN-W-CURRENT-009-3-CMP")
			.andExpect(jsonPath("$.data.data.r1.side").value("ABOVE_UCL"))
			.andExpect(jsonPath("$.data.data.oos").value(false));
		iget("/internal/runs/RUN-W-CURRENT-007-3-CMP/features")
			.andExpect(jsonPath("$.data.data.outsideReferenceBand", contains("slurry_flow")));
		iget("/internal/runs/RUN-W-CURRENT-007-3-CMP/traces/slurry_flow?step=PROCESS&maxPoints=10")
			.andExpect(jsonPath("$.data.data.sampleCount").value(300))
			.andExpect(jsonPath("$.data.data.points", hasSize(10)))
			.andExpect(jsonPath("$.data.data.summary.mean").value(lessThan(120.0)));
		iget("/internal/limits?processCode=CMP")
			.andExpect(jsonPath("$.data.data.qualityLimits[0].limitId").value("QL-CMP-V1"))
			.andExpect(jsonPath("$.data.data.featureReferenceBands", hasSize(5)));
		iget("/internal/changes?equipmentId=ETCH-PM2")
			.andExpect(jsonPath("$.data.data.items[0].changeId").value("DEMO-CHANGE-E02"))
			.andExpect(jsonPath("$.data.data.items[0].rootCauseConfirmed").value(false));
		iget("/internal/processes/CMP/owners")
			.andExpect(jsonPath("$.data.data.items[*].kind", contains("PROCESS_OWNER", "MAINTENANCE")))
			.andExpect(jsonPath("$.data.data.items[0].displayName").value("(가림)"));
	}

	@Test
	void analysisTimeAndBaselineRulesAreEnforced() throws Exception {
		// 분석 시점(팩 analysisAsOf) 이후 run · FOLLOWUP은 막는다, BASELINE은 원시 시계열이 없다
		iget("/internal/runs/RUN-W-FOLLOWUP-001-1-CMP").andExpect(status().isForbidden())
			.andExpect(jsonPath("$.error.code").value("FORBIDDEN"));
		iget("/internal/runs/RUN-W-BASELINE-001-1-CMP/traces/head_pressure").andExpect(status().isNotFound())
			.andExpect(jsonPath("$.error.hint", containsString("get_run_features")));
		iget("/internal/runs/RUN-W-BASELINE-001-1-CMP/features").andExpect(status().isOk());
		iget("/internal/incidents/EV-9999").andExpect(status().isNotFound())
			.andExpect(jsonPath("$.error.code").value("NOT_FOUND")).andExpect(jsonPath("$.error.hint", containsString("EV-1009")));
		iget("/internal/limits?processCode=PHOTO").andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.error.retryable").value(false));
	}

	@Test
	void agentProposalIsRecordedOnApprovalAndExecutedOnceByApprovalId() throws Exception {
		Session cmp = cmp();
		String id = newIncident(cmp, false).incidentId();
		// 수동으로 질문 작업을 만들고(시뮬레이터가 처리) 그 작업에 Agent 제안을 붙인다
		String jobId = json(postAs(cmp, "/api/incidents/{id}/questions",
			"{\"question\":\"그냥 질문\",\"scope\":\"CURRENT\",\"expectedIncidentVersion\":" + incidentVersion(cmp, id) + "}", id), "$.data.jobId");
		awaitJob(cmp, jobId, "SUCCEEDED");
		String body = """
			{"actionType":"CREATE_CHECK_REQUEST","payload":{"ownerUserId":"USR-ETCH-01","targetProcessCode":"ETCH","question":"ETCH-PM2 정비 · 시즈닝 확인"},
			 "rationale":"Etch 제거량 정상 변동 밖","evidenceIds":["E-RUN-RUN-W-CURRENT-009-3-ETCH"],"idempotencyKey":"%s:call_1"}""".formatted(jobId);
		String approvalId = json(ipost("/internal/jobs/{id}/proposals", body, jobId).andExpect(status().isCreated())
			.andExpect(jsonPath("$.data.actionType").value("CREATE_CHECK_REQUEST"))
			.andExpect(jsonPath("$.data.status").value("PENDING")), "$.data.approvalId");
		ipost("/internal/jobs/{id}/proposals", body, jobId).andExpect(jsonPath("$.data.approvalId").value(approvalId));

		String exec = "{\"approvalId\":\"" + approvalId + "\",\"idempotencyKey\":\"key-00000001\"}";
		ipost("/internal/approved-actions/check-request", exec).andExpect(status().isConflict())
			.andExpect(jsonPath("$.error.code").value("APPROVAL_REQUIRED"));
		ipost("/internal/approved-actions/action-record", exec).andExpect(status().isForbidden());

		postAs(cmp, "/api/approvals/{id}/decisions", "{\"decision\":\"APPROVE\",\"expectedVersion\":1}", approvalId)
			.andExpect(status().isOk());
		String requestId = json(ipost("/internal/approved-actions/check-request", exec).andExpect(status().isOk())
			.andExpect(jsonPath("$.data.data.status").value("OPEN"))
			.andExpect(jsonPath("$.data.data.ownerUserId").value("USR-ETCH-01")), "$.data.data.requestId");
		ipost("/internal/approved-actions/check-request",
			"{\"approvalId\":\"" + approvalId + "\",\"idempotencyKey\":\"key-00000002\"}")
			.andExpect(jsonPath("$.data.data.requestId").value(requestId));
		iget("/internal/incidents/{id}/check-requests", id).andExpect(jsonPath("$.data.data.items", hasSize(1)));
		iget("/internal/approvals/{id}", approvalId).andExpect(jsonPath("$.data.status").value("APPROVED"))
			.andExpect(jsonPath("$.data.executionRef.id").value(requestId));
	}

	@Test
	void requestBriefIsHashedCopiedAndShownToRecipient() throws Exception {
		Session cmp = cmp();
		String id = newIncident(cmp, false).incidentId();
		String jobId = json(postAs(cmp, "/api/incidents/{id}/questions",
			"{\"question\":\"브리프\",\"scope\":\"CURRENT\",\"expectedIncidentVersion\":" + incidentVersion(cmp, id) + "}", id),
			"$.data.jobId");
		awaitJob(cmp, jobId, "SUCCEEDED");
		String tpl = """
			{"actionType":"CREATE_CHECK_REQUEST","payload":{"ownerUserId":"USR-ETCH-01","targetProcessCode":"ETCH",
			 "equipmentId":"ETCH-PM2","question":"ETCH-PM2 run 확인 부탁","causeIds":["ETCH_WALL"],
			 "brief":{"reason":"%s","deviations":[{"runId":"RUN-W-CURRENT-009-3-ETCH","equipmentId":"ETCH-PM2","metric":"removedA",
			   "value":4896.3,"unit":"Å","baselineMean":4999.6,"z":-2.76,"threshold":2.5,"thresholdKind":"ABS_Z_SAME_PM","withinNormal":false}],
			   "suspectedCauses":[{"causeId":"ETCH_WALL","candidate":"챔버 표면·세정 이후 상태 변화"}],
			   "relatedRecords":[{"evidenceId":"E-CHG-ETCH-PM2","summary":"DEMO-CHANGE-E02 CHAMBER_SERVICE","rootCauseConfirmed":false}],
			   "askFor":["정비 후 시즈닝 매수","절차","모니터 결과"]}},
			 "rationale":"r","evidenceIds":["E-RUN-RUN-W-CURRENT-009-3-ETCH"],"idempotencyKey":"%s"}""";
		String a1 = json(ipost("/internal/jobs/{id}/proposals", tpl.formatted("Etch 제거량이 정상 변동 밖", "brief-1"), jobId)
			.andExpect(status().isCreated())
			.andExpect(jsonPath("$.data.payload.brief.suspectedCauses[0].confirmed").value(false))
			.andExpect(jsonPath("$.data.payload.causeIds", contains("ETCH_WALL"))), "$.data.approvalId");
		// Agent 승인은 사건당 하나다. 해시 비교용 두 번째 문서는 독립적인 사람의 수동 제안으로 만든다.
		java.util.Map<String,Object> otherPayload = com.jayway.jsonpath.JsonPath.read(tpl.formatted("문장만 다른 브리프", "brief-2"), "$.payload");
		String a2 = json(postAs(cmp, "/api/incidents/{id}/proposals", com.fabcontext.api.common.Json.write(java.util.Map.of(
			"actionType","CHECK_REQUEST", "payload",otherPayload, "rationale","r", "jobId",jobId,
			"expectedIncidentVersion",incidentVersion(cmp,id))), id).andExpect(status().isCreated()), "$.data.approvalId");
		// 승인 화면(P08)에 브리프가 보이고, 해시가 브리프까지 덮는다 — 문장 하나만 달라도 해시가 다르다
		String h1 = json(getAs(cmp, "/api/approvals/{id}", a1).andExpect(jsonPath("$.data.payload.brief.reason").value("Etch 제거량이 정상 변동 밖"))
			.andExpect(jsonPath("$.data.payload.brief.deviations[0].value").value(4896.3)), "$.data.actionHash");
		String h2 = json(getAs(cmp, "/api/approvals/{id}", a2), "$.data.actionHash");
		org.assertj.core.api.Assertions.assertThat(h1).isNotEqualTo(h2);
		postAs(cmp, "/api/approvals/{id}/decisions", "{\"decision\":\"APPROVE\",\"expectedVersion\":1,\"actionHash\":\"" + h2 + "\"}", a1)
			.andExpect(status().isConflict()).andExpect(jsonPath("$.error.code").value("ACTION_HASH_MISMATCH"));
		postAs(cmp, "/api/approvals/{id}/decisions", "{\"decision\":\"APPROVE\",\"expectedVersion\":1,\"actionHash\":\"" + h1 + "\"}", a1)
			.andExpect(status().isOk());
		String req = json(ipost("/internal/approved-actions/check-request",
			"{\"approvalId\":\"" + a1 + "\",\"idempotencyKey\":\"brief-exec-1\"}").andExpect(status().isOk()), "$.data.data.requestId");
		// 확인 요청(P09) — 받는 사람(etch.lee)은 사건 분석 권한 없이 브리프만 본다
		Session etch = login("etch.lee");
		getAs(etch, "/api/check-requests/{id}", req).andExpect(status().isOk())
			.andExpect(jsonPath("$.data.equipmentId").value("ETCH-PM2"))
			.andExpect(jsonPath("$.data.causeIds", contains("ETCH_WALL")))
			.andExpect(jsonPath("$.data.brief.reason").value("Etch 제거량이 정상 변동 밖"))
			.andExpect(jsonPath("$.data.brief.relatedRecords[0].rootCauseConfirmed").value(false))
			.andExpect(jsonPath("$.data.brief.askFor", hasSize(3)));
		getAs(etch, "/api/incidents/{id}/analysis-results", id).andExpect(status().isForbidden());
		iget("/internal/incidents/{id}/check-requests", id).andExpect(jsonPath("$.data.data.items[0].brief.reason").exists());
	}

	@Test
	void briefCannotConfirmACause() throws Exception {
		Session cmp = cmp();
		String id = newIncident(cmp, false).incidentId();
		String jobId = json(postAs(cmp, "/api/incidents/{id}/questions",
			"{\"question\":\"확정 금지\",\"scope\":\"CURRENT\",\"expectedIncidentVersion\":" + incidentVersion(cmp, id) + "}", id),
			"$.data.jobId");
		awaitJob(cmp, jobId, "SUCCEEDED");
		ipost("/internal/jobs/{id}/proposals", """
			{"actionType":"CREATE_CHECK_REQUEST","payload":{"ownerUserId":"USR-ETCH-01","targetProcessCode":"ETCH","question":"q",
			 "brief":{"reason":"r","suspectedCauses":[{"causeId":"ETCH_WALL","confirmed":true}]}},"idempotencyKey":"brief-bad"}""", jobId)
			.andExpect(status().is4xxClientError()).andExpect(jsonPath("$.error.message", containsString("확정")));
	}

	@Test
	void proposalRecipientIsCheckedByBackend() throws Exception {
		Session cmp = cmp();
		String id = newIncident(cmp, false).incidentId();
		String jobId = json(postAs(cmp, "/api/incidents/{id}/questions",
			"{\"question\":\"받는 사람 검증\",\"scope\":\"CURRENT\",\"expectedIncidentVersion\":" + incidentVersion(cmp, id) + "}", id),
			"$.data.jobId");
		awaitJob(cmp, jobId, "SUCCEEDED");
		String tpl = """
			{"actionType":"%s","payload":{"ownerUserId":"%s","targetProcessCode":"%s","question":"확인 부탁드립니다"},
			 "rationale":"r","evidenceIds":[],"idempotencyKey":"%s"}""";
		// 질문한 본인(CMP 담당)에게는 보내지 않는다
		ipost("/internal/jobs/{id}/proposals", tpl.formatted("CREATE_MAINTENANCE_REQUEST", "USR-CMP-01", "CMP", "k1"), jobId)
			.andExpect(status().isBadRequest()).andExpect(jsonPath("$.error.message", containsString("질문한 사람")));
		// ETCH 확인 요청을 CVD 담당에게 보내면 반려
		ipost("/internal/jobs/{id}/proposals", tpl.formatted("CREATE_CHECK_REQUEST", "USR-CVD-01", "ETCH", "k2"), jobId)
			.andExpect(status().isBadRequest()).andExpect(jsonPath("$.error.hint", containsString("PROCESS_OWNER")));
		// 앞 공정 확인 요청을 사건 공정(CMP)으로 보내면 반려 — 현재 공정은 정비 요청
		ipost("/internal/jobs/{id}/proposals", tpl.formatted("CREATE_CHECK_REQUEST", "USR-ETCH-01", "CMP", "k3"), jobId)
			.andExpect(status().isBadRequest()).andExpect(jsonPath("$.error.hint", containsString("propose_maintenance_request")));
		// 정비 요청은 설비정비팀에게만
		ipost("/internal/jobs/{id}/proposals", tpl.formatted("CREATE_MAINTENANCE_REQUEST", "USR-ETCH-01", "CMP", "k4"), jobId)
			.andExpect(status().isBadRequest()).andExpect(jsonPath("$.error.hint", containsString("MAINTENANCE")));
		ipost("/internal/jobs/{id}/proposals", tpl.formatted("CREATE_MAINTENANCE_REQUEST", "USR-MNT-01", "CMP", "k5"), jobId)
			.andExpect(status().isCreated());
	}

	@Test
	void claimsBlockDuplicateAttemptsAndEventsDriveJobState() throws Exception {
		Session cmp = cmp();
		String id = newIncident(cmp, false).incidentId();
		String jobId = json(postAs(cmp, "/api/incidents/{id}/report-jobs",
			"{\"expectedIncidentVersion\":" + incidentVersion(cmp, id) + "}", id), "$.data.jobId");
		awaitJob(cmp, jobId, "SUCCEEDED"); // 시뮬레이터 모드에서는 끝난 작업 — 확보 거절
		ipost("/internal/jobs/{id}/claims", "{\"attempt\":1,\"workerId\":\"w1\"}", jobId).andExpect(status().isConflict())
			.andExpect(jsonPath("$.error.code").value("CONFLICT"));
		iget("/internal/jobs/{id}/context", jobId).andExpect(jsonPath("$.data.job.kind").value("REPORT"))
			.andExpect(jsonPath("$.data.job.schemaVersion").value("1.0"))
			.andExpect(jsonPath("$.data.job.resume").doesNotExist());
		ipost("/internal/jobs/{id}/events", "{\"eventId\":\"EVT-" + jobId + "-FAILED\",\"attempt\":1,\"type\":\"JOB_FAILED\",\"payload\":{}}", jobId)
			.andExpect(jsonPath("$.data.applied").value(false));
	}

	@Test
	void eventMetricsAreStoredOnJobWithEarlierAttemptsKept() throws Exception {
		// 대기열 이벤트를 내지 않고 바로 저장한 작업 — 시뮬레이터가 가져가지 않아 외부 Agent 이벤트만 받는다
		String incidentId = jdbc.queryForObject("select incident_id from incident where display_id = 'EV-1009'", String.class);
		String jobId = "job_metrics_" + System.nanoTime();
		jobs.save(Job.builder().jobId(jobId).incidentId(incidentId).kind(JobKind.DEEP).attempt(1)
			.threadId("thr_metrics").question("q").createdAt(Instant.now()).build());
		String tpl = """
			{"eventId":"EVT-%s","attempt":%d,"type":"ANALYSIS_PROGRESS","payload":{"stepCode":"THINKING","message":"m","progressRatio":0.5},
			 "metrics":{"attempt":%d,"modelCalls":%d,"inputTokens":%d,"outputTokens":40,"apiModels":["gpt-4.1-mini-2025-04-14"],
			            "guardRejects":1,"ms":1200.5}}""";
		ipost("/internal/jobs/{id}/events", tpl.formatted(jobId + "-A1", 1, 1, 3, 1000), jobId).andExpect(status().isOk());
		ipost("/internal/jobs/{id}/events", """
			{"eventId":"EVT-%s-retry","attempt":1,"type":"RETRY_SCHEDULED","payload":{"nextAttempt":2}}
			""".formatted(jobId), jobId).andExpect(status().isOk());
		ipost("/internal/jobs/{id}/events", tpl.formatted(jobId + "-A2-1", 2, 2, 5, 2000), jobId).andExpect(status().isOk());
		// 최신 시도가 위에, 앞 시도는 previousAttempts — jsonb 연산자로 바로 조회된다(DBeaver · Grafana)
		var row = jdbc.queryForMap("""
			select metrics->>'attempt' as attempt, (metrics->>'inputTokens')::int as input_tokens,
			       metrics->'apiModels'->>0 as model, jsonb_array_length(metrics->'previousAttempts') as previous,
			       metrics->'previousAttempts'->0->>'inputTokens' as previous_input
			from job where job_id = ?""", jobId);
		org.assertj.core.api.Assertions.assertThat(row).containsEntry("attempt", "2").containsEntry("input_tokens", 2000)
			.containsEntry("model", "gpt-4.1-mini-2025-04-14").containsEntry("previous", 1).containsEntry("previous_input", "1000");
		// 같은 시도가 다시 오면 덮는다(쌓지 않는다)
		ipost("/internal/jobs/{id}/events", tpl.formatted(jobId + "-A2-2", 2, 2, 6, 2500), jobId).andExpect(status().isOk());
		org.assertj.core.api.Assertions.assertThat(jdbc.queryForObject(
			"select jsonb_array_length(metrics->'previousAttempts') from job where job_id = ?", Integer.class, jobId)).isEqualTo(1);
	}

	@Test
	void agentReportDraftNeedsAllFiveSections() throws Exception {
		ipost("/internal/incidents/EV-1009/report-drafts", "{\"sections\":{\"overview\":\"o\"}}")
			.andExpect(status().isBadRequest()).andExpect(jsonPath("$.error.message", containsString("investigation")));
		ipost("/internal/incidents/EV-1009/report-drafts", """
			{"sections":{"overview":"o","investigation":"i","requestsAndReplies":"r","actionsAndVerification":"a",
			 "unresolved":"u"},"evidenceIds":["E-INC"]}""").andExpect(status().isCreated())
			.andExpect(jsonPath("$.data.status").value("DRAFT"))
			.andExpect(jsonPath("$.data.sections.requestsAndReplies").value(startsWith("r")))
			.andExpect(jsonPath("$.data.sections.requestsAndReplies").value(containsString("담당자·요청·검토 이력")));
	}
}
