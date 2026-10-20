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

@DisplayName("P07 AI 조사 · 작업 · 승인 대기 · 회신 후 재개")
class InvestigationApiTest extends ApiTestSupport {

	@Test
	void basicAnalysisSeparatesFactsHypothesesAndLimits() throws Exception {
		Session etch = cmp();
		// L006(팩 README 4절): CMP 헤드 압력 저하 → 제거량 감소 — 현재 공정 후보가 1순위, 원인 확정 아님
		getAs(etch, "/api/incidents/{id}/analysis-results", seededIncident(etch, "EV-1006"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.kind").value("BASIC"))
			.andExpect(jsonPath("$.data.summary", containsString("원인은 확정하지 않았습니다")))
			.andExpect(jsonPath("$.data.observedFacts[*].statement", hasItem(containsString("head_pressure 본 처리 평균"))))
			.andExpect(jsonPath("$.data.candidateCauses[0].relation").value("CURRENT"))
			.andExpect(jsonPath("$.data.candidateCauses[0].matrixCauseIds", contains("CMP_PRESSURE")))
			.andExpect(jsonPath("$.data.candidateCauses[0].supportingEvidenceIds",
				hasItem("ev:feature:RUN-W-CURRENT-006-3-CMP:head_pressure")))
			.andExpect(jsonPath("$.data.limitations", hasItem(containsString("확률이 아닙니다"))))
			.andExpect(jsonPath("$.data.evidenceRefs[*].type", hasItems("METROLOGY", "SENSOR")))
			.andExpect(jsonPath("$.data.history").doesNotExist());
	}

	@Test
	void fullAgentLoopWithApprovalReplyAndResume() throws Exception {
		Session etch = cmp();
		String id = newIncident(etch, false).incidentId();
		getAs(etch, "/api/incidents/{id}", id).andExpect(jsonPath("$.data.status").value("INVESTIGATING"))
			.andExpect(jsonPath("$.data.activeJobId").doesNotExist());

		String question = "{\"question\":\"이전 공정(ETCH) 영향도 확인해줘\",\"expectedIncidentVersion\":"
				+ incidentVersion(etch, id) + "}";
		String key = UUID.randomUUID().toString();
		var accepted = postWithKey(etch, key, "/api/incidents/{id}/questions", question, id)
			.andExpect(status().isAccepted())
			.andExpect(header().string("Location", startsWith("/api/jobs/")))
			.andExpect(jsonPath("$.data.kind").value("DEEP"))
			.andExpect(jsonPath("$.data.status").value("QUEUED"))
			.andExpect(jsonPath("$.data.streamUrl").value("/api/incidents/" + id + "/stream"));
		String jobId = json(accepted, "$.data.jobId");
		postWithKey(etch, key, "/api/incidents/{id}/questions", question, id)
			.andExpect(header().string("Idempotent-Replayed", "true"))
			.andExpect(jsonPath("$.data.jobId").value(jobId));

		awaitJob(etch, jobId, "WAITING_APPROVAL");
		String approvalId = waitingRefId(etch, jobId);
		String threadId = json(getAs(etch, "/api/jobs/{id}", jobId), "$.data.threadId");
		var approval = getAs(etch, "/api/approvals/{id}", approvalId)
			.andExpect(jsonPath("$.data.source").value("AGENT"))
			.andExpect(jsonPath("$.data.jobId").value(jobId))
			.andExpect(jsonPath("$.data.payload.ownerUserId").value("USR-ETCH-01"))
			.andExpect(jsonPath("$.data.payload.targetProcessCode").value("ETCH"))
			.andExpect(jsonPath("$.data.actionHash").value(startsWith("sha256:")));
		// 승인 전에는 실제 업무(확인 요청)가 만들어지지 않는다
		getAs(login("etch.lee"), "/api/check-requests?box=received&incidentId=" + id).andExpect(jsonPath("$.data.items", hasSize(0)));

		var decision = postAs(etch, "/api/approvals/{id}/decisions", "{\"decision\":\"APPROVE\",\"expectedVersion\":"
				+ number(approval, "$.data.version") + ",\"actionHash\":\"" + json(approval, "$.data.actionHash") + "\"}",
				approvalId)
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.approval.status").value("APPROVED"))
			// Agent가 기다리는 제안은 승인만 기록한다 — 실행은 재개된 Agent가 승인 ID로 한다
			.andExpect(jsonPath("$.data.executionRef").doesNotExist());
		String resume1 = json(decision, "$.data.resumeJobId");
		getAs(etch, "/api/jobs/{id}", jobId).andExpect(jsonPath("$.data.status").value("SUCCEEDED"))
			.andExpect(jsonPath("$.data.resultRef.id").value(resume1));
		awaitJob(etch, resume1, "WAITING_REPLY");
		String requestId = json(getAs(etch, "/api/approvals/{id}", approvalId)
			.andExpect(jsonPath("$.data.executionRef.type").value("CHECK_REQUEST")), "$.data.executionRef.id");
		getAs(etch, "/api/jobs/{id}", resume1).andExpect(jsonPath("$.data.kind").value("RESUME"))
			.andExpect(jsonPath("$.data.threadId").value(threadId))
			.andExpect(jsonPath("$.data.parentJobId").value(jobId))
			.andExpect(jsonPath("$.data.waitingRef.id").value(requestId));
		getAs(etch, "/api/incidents/{id}", id).andExpect(jsonPath("$.data.status").value("WAITING_REPLY"))
			.andExpect(jsonPath("$.data.activeJobId").value(resume1));

		Session photo = login("etch.lee");
		getAs(photo, "/api/check-requests/{id}", requestId)
			.andExpect(jsonPath("$.data.waitingJobId").value(resume1))
			.andExpect(jsonPath("$.data.threadId").value(threadId))
			.andExpect(jsonPath("$.data.incident.incidentId").value(id));
		String resume2 = json(postAs(photo, "/api/check-requests/{id}/replies",
				"{\"findings\":\"ETCH-PM2 정비 후 시즈닝 매수 부족 확인\",\"confirmedByUser\":true,\"expectedVersion\":1}", requestId)
			.andExpect(status().isCreated()), "$.data.resumeJobId");
		awaitJob(etch, resume2, "SUCCEEDED");
		getAs(etch, "/api/jobs/{id}", resume2).andExpect(jsonPath("$.data.threadId").value(threadId))
			.andExpect(jsonPath("$.data.resultRef.type").value("ANALYSIS_RESULT"));

		getAs(etch, "/api/incidents/{id}/analysis-results?latest=false", id)
			.andExpect(jsonPath("$.data.kind").value("RESUME"))
			.andExpect(jsonPath("$.data.observedFacts[*].statement", hasItem(containsString("담당자 회신"))))
			.andExpect(jsonPath("$.data.candidateCauses[?(@.relation=='UPSTREAM')].status", contains("OWNER_REPLIED")))
			.andExpect(jsonPath("$.data.history[*].kind", contains("RESUME", "DEEP", "BASIC")));
		getAs(etch, "/api/incidents/{id}", id)
			.andExpect(jsonPath("$.data.status").value("INVESTIGATING"))
			.andExpect(jsonPath("$.data.timeline[*].type", hasItems("QUESTION_ASKED", "PROPOSAL_CREATED",
				"APPROVAL_APPROVED", "CHECK_REQUEST_SENT", "REPLY_RECEIVED", "ANALYSIS_COMPLETED")));
	}

	@Test
	void currentProcessQuestionCompletesWithoutProposal() throws Exception {
		Session etch = cmp();
		String id = newIncident(etch, false).incidentId();
		String jobId = json(postAs(etch, "/api/incidents/{id}/questions",
				"{\"question\":\"CMP 헤드 압력 점검 관련 근거 정리\",\"scope\":\"CURRENT\",\"expectedIncidentVersion\":"
						+ incidentVersion(etch, id) + "}", id), "$.data.jobId");
		awaitJob(etch, jobId, "SUCCEEDED");
		getAs(etch, "/api/incidents/{id}/analysis-results", id)
			.andExpect(jsonPath("$.data.kind").value("DEEP"))
			.andExpect(jsonPath("$.data.question").value(containsString("헤드 압력")))
			.andExpect(jsonPath("$.data.evidenceRefs[*].type", hasItem("DOCUMENT")));
		getAs(etch, "/api/approvals?incidentId=" + id).andExpect(jsonPath("$.data.items", hasSize(0)));
	}

	@Test
	void rejectedAgentProposalEndsWaitWithoutExecuting() throws Exception {
		Session etch = cmp();
		String id = newIncident(etch, false).incidentId();
		String jobId = json(postAs(etch, "/api/incidents/{id}/questions",
				"{\"question\":\"이전 공정 확인\",\"expectedIncidentVersion\":" + incidentVersion(etch, id) + "}", id),
				"$.data.jobId");
		awaitJob(etch, jobId, "WAITING_APPROVAL");
		String approvalId = waitingRefId(etch, jobId);
		postAs(etch, "/api/approvals/{id}/decisions", "{\"decision\":\"REJECT\",\"expectedVersion\":1}", approvalId)
			.andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.error.code").value("REJECT_REASON_REQUIRED"));
		postAs(etch, "/api/approvals/{id}/decisions",
				"{\"decision\":\"REJECT\",\"comment\":\"질문 대상이 틀림\",\"expectedVersion\":1}", approvalId)
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.approval.status").value("REJECTED"))
			.andExpect(jsonPath("$.data.resumeJobId").doesNotExist())
			.andExpect(jsonPath("$.data.executionRef").doesNotExist());
		getAs(etch, "/api/jobs/{id}", jobId).andExpect(jsonPath("$.data.status").value("SUCCEEDED"))
			.andExpect(jsonPath("$.data.resultRef.type").value("APPROVAL_REJECTED"));
		getAs(etch, "/api/check-requests?box=sent&incidentId=" + id).andExpect(jsonPath("$.data.items", hasSize(0)));
	}

	@Test
	void analysisFailureDoesNotBlockRawDataAndCanBeRerun() throws Exception {
		Session etch = cmp();
		NewIncident created = newIncident(etch, true);
		String id = created.incidentId();
		getAs(etch, "/api/jobs/{id}", created.jobId())
			.andExpect(jsonPath("$.data.error.code").value("AGENT_ERROR"))
			.andExpect(jsonPath("$.data.attempt").value(1));
		getAs(etch, "/api/incidents/{id}", id).andExpect(jsonPath("$.data.activeJobId").value(created.jobId()))
			.andExpect(jsonPath("$.data.activeJob.status").value("FAILED"))
			.andExpect(jsonPath("$.data.timeline[*].type", hasItem("ANALYSIS_FAILED")));
		getAs(etch, "/api/incidents?jobStatus=FAILED&q=" + id).andExpect(jsonPath("$.data.items", hasSize(1)));
		getAs(etch, "/api/incidents/{id}/analysis-results", id).andExpect(status().isNotFound())
			.andExpect(jsonPath("$.error.code").value("ANALYSIS_NOT_READY"))
			.andExpect(jsonPath("$.error.details.jobStatus").value("FAILED"));
		getAs(etch, "/api/incidents/{id}/sensor-data", id).andExpect(status().isOk());

		String rerun = json(postAs(etch, "/api/incidents/{id}/basic-analysis-jobs",
				"{\"expectedIncidentVersion\":" + incidentVersion(etch, id) + ",\"reason\":\"재시도\"}", id)
			.andExpect(status().isAccepted()), "$.data.jobId");
		awaitJob(etch, rerun, "SUCCEEDED");
		getAs(etch, "/api/jobs/{id}", rerun).andExpect(jsonPath("$.data.attempt").value(2));
		getAs(etch, "/api/incidents/{id}", id).andExpect(jsonPath("$.data.activeJobId").doesNotExist());
		getAs(etch, "/api/incidents/{id}/analysis-results", id).andExpect(status().isOk());
	}

	@Test
	void failMarkerInQuestionFailsDeepJob() throws Exception {
		Session etch = cmp();
		String id = newIncident(etch, false).incidentId();
		String jobId = json(postAs(etch, "/api/incidents/{id}/questions",
				"{\"question\":\"[fail] 실패 시연\",\"expectedIncidentVersion\":" + incidentVersion(etch, id) + "}", id),
				"$.data.jobId");
		awaitJob(etch, jobId, "FAILED");
	}

	@Test
	void questionValidationAndIdempotencyRules() throws Exception {
		Session etch = cmp();
		String id = seededIncident(etch, "EV-1006");
		long v = incidentVersion(etch, id);
		postAs(etch, "/api/incidents/{id}/questions", "{\"question\":\"\",\"expectedIncidentVersion\":" + v + "}", id)
			.andExpect(status().isUnprocessableEntity());
		postAs(etch, "/api/incidents/{id}/questions", "{\"question\":\"q\"}", id).andExpect(status().isUnprocessableEntity());
		postAs(etch, "/api/incidents/{id}/questions", "{\"question\":\"q\",\"scope\":\"ALL\",\"expectedIncidentVersion\":" + v + "}", id)
			.andExpect(status().isUnprocessableEntity());
		postAs(etch, "/api/incidents/{id}/questions", "{\"question\":\"q\",\"expectedIncidentVersion\":" + (v + 5) + "}", id)
			.andExpect(status().isConflict()).andExpect(jsonPath("$.error.code").value("VERSION_CONFLICT"));
		postAs(etch, "/api/incidents/{id}/questions", "not json", id).andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.error.code").value("MALFORMED_BODY"));
		mvc.perform(post("/api/incidents/{id}/questions", id).session(etch.http()).header("X-CSRF-TOKEN", etch.csrf())
				.contentType("application/json").content("{\"question\":\"q\",\"expectedIncidentVersion\":" + (v + 5) + "}"))
			.andExpect(status().isBadRequest()).andExpect(jsonPath("$.error.code").value("MISSING_HEADER"));
		postWithKey(etch, "short", "/api/incidents/{id}/questions", "{\"question\":\"q\",\"expectedIncidentVersion\":1}", id)
			.andExpect(status().isBadRequest()).andExpect(jsonPath("$.error.code").value("INVALID_IDEMPOTENCY_KEY"));
		String key = UUID.randomUUID().toString();
		// 실패 응답은 저장하지 않으므로 같은 키로 올바른 요청을 다시 보낼 수 있다
		postWithKey(etch, key, "/api/incidents/{id}/questions", "{\"question\":\"q\",\"expectedIncidentVersion\":0}", id)
			.andExpect(status().isConflict());
		postWithKey(etch, key, "/api/incidents/{id}/questions", "{\"question\":\"q\",\"expectedIncidentVersion\":0}", id)
			.andExpect(status().isConflict())
			.andExpect(header().doesNotExist("Idempotent-Replayed"));
	}

	@Test
	void jobAccessFollowsIncidentScope() throws Exception {
		Session etch = cmp();
		NewIncident created = newIncident(etch, false);
		getAs(cvd(), "/api/jobs/{id}", created.jobId()).andExpect(status().isForbidden());
		getAs(etch, "/api/jobs/{id}", "job_missing").andExpect(status().isNotFound())
			.andExpect(jsonPath("$.error.code").value("JOB_NOT_FOUND"));
	}

	@Test
	void streamSendsSnapshotThenLiveEvents() throws Exception {
		Session etch = cmp();
		String id = newIncident(etch, false).incidentId();
		var stream = getAs(etch, "/api/incidents/{id}/stream", id).andExpect(status().isOk()).andReturn();
		assertThat(stream.getRequest().isAsyncStarted()).isTrue();
		assertThat(stream.getResponse().getContentType()).startsWith("text/event-stream");
		assertThat(stream.getResponse().getContentAsString()).contains("event:snapshot").contains("\"status\":\"INVESTIGATING\"");

		String jobId = json(postAs(etch, "/api/incidents/{id}/questions",
				"{\"question\":\"현재 공정 확인\",\"scope\":\"CURRENT\",\"expectedIncidentVersion\":" + incidentVersion(etch, id) + "}",
				id), "$.data.jobId");
		awaitJob(etch, jobId, "SUCCEEDED");
		String events = stream.getResponse().getContentAsString();
		assertThat(events).contains("event:job.status").contains("\"status\":\"RUNNING\"").contains("event:analysis.result");
		getAs(cvd(), "/api/incidents/{id}/stream", id).andExpect(status().isForbidden());
	}
}
