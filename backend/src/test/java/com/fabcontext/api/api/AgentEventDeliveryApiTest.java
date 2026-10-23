package com.fabcontext.api.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import com.fabcontext.api.common.Json;
import com.fabcontext.api.domain.job.Job;
import com.fabcontext.api.domain.job.JobKind;
import com.fabcontext.api.domain.job.JobRepository;
import com.fabcontext.api.domain.job.JobStatus;
import com.fabcontext.api.domain.job.ProcessedAgentEventRepository;
import com.fabcontext.api.support.ApiTestSupport;
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.ResultActions;

@DisplayName("Agent 이벤트 수신 — 중복 · 이전 시도 차단 · 동시 수신 · 처리 실패 롤백")
@TestPropertySource(properties = {"fab.agent.mode=external",
		"spring.jpa.properties.hibernate.default_schema=backend_ext", "spring.datasource.hikari.schema=backend_ext"})
class AgentEventDeliveryApiTest extends ApiTestSupport {

	private static final String BEARER = "Bearer dev-service-token";

	@Autowired JobRepository jobs;
	@Autowired ProcessedAgentEventRepository processedEvents;
	@Autowired JdbcTemplate jdbc;

	private Job newJob() throws Exception {
		// 실행 직렬화가 적용되므로 공유 시드 사건에 여러 실행을 직접 끼워 넣지 않는다.
		// 각 테스트가 실제 접수 경로로 독립 사건·기본 작업 하나를 만든다.
		return jobs.findById(externalIncident().jobId()).orElseThrow();
	}

	private Map<String, Object> event(Job job, String id, int attempt, String type, Map<String, Object> payload) {
		return Map.of("schemaVersion", "1.0", "eventId", id, "jobId", job.getJobId(),
			"incidentId", job.getIncidentId(), "threadId", job.getThreadId(), "attempt", attempt,
			"type", type, "payload", payload, "at", Instant.now().toString(), "traceId", "TRACE-" + job.getJobId());
	}

	private ResultActions send(Job job, Map<String, Object> event) throws Exception {
		return mvc.perform(post("/internal/jobs/{id}/events", job.getJobId()).header("Authorization", BEARER)
			.contentType(MediaType.APPLICATION_JSON).content(Json.write(event)));
	}

	private void start(Job job, int attempt) throws Exception {
		send(job, event(job, "EVT-" + job.getJobId() + "-A" + attempt + "-STARTED", attempt, "JOB_STARTED", Map.of()))
			.andExpect(status().isOk()).andExpect(jsonPath("$.data.applied").value(true));
	}

	@Test
	void duplicateRetryIsIgnoredAfterTheNextAttemptHasStarted() throws Exception {
		Job job = newJob();
		start(job, 1);
		String eventId = "EVT-" + job.getJobId() + "-RETRY";
		var retry = event(job, eventId, 1, "RETRY_SCHEDULED", Map.of("nextAttempt", 2));
		send(job, retry).andExpect(status().isOk()).andExpect(jsonPath("$.data.applied").value(true));
		start(job, 2);

		// 다음 HTTP 요청의 새 영속성 컨텍스트에서도 DB의 수신 기록을 읽어 중복을 막는다.
		send(job, retry).andExpect(status().isOk()).andExpect(jsonPath("$.data.applied").value(false))
			.andExpect(jsonPath("$.data.reason").value("DUPLICATE_EVENT"));
		Job current = jobs.findById(job.getJobId()).orElseThrow();
		assertThat(current.getStatus()).isEqualTo(JobStatus.RUNNING);
		assertThat(current.getAttempt()).isEqualTo(2);
		assertThat(processedEvents.findById(eventId)).hasValueSatisfying(receipt -> {
			assertThat(receipt.getJobId()).isEqualTo(job.getJobId());
			assertThat(receipt.getAttempt()).isEqualTo(1);
		});
	}

	@Test
	void staleAndFutureAttemptsCannotChangeTheCurrentJobOrMetrics() throws Exception {
		Job job = newJob();
		start(job, 1);
		send(job, event(job, "EVT-" + job.getJobId() + "-RETRY", 1, "RETRY_SCHEDULED", Map.of("nextAttempt", 2)))
			.andExpect(status().isOk());
		start(job, 2);
		for (int attempt : List.of(1, 3)) {
			String id = "EVT-" + job.getJobId() + "-A" + attempt + "-FAILED";
			Map<String, Object> failure = new java.util.LinkedHashMap<>(event(job, id, attempt, "JOB_FAILED",
				Map.of("errorCode", "LATE", "message", "not the current attempt")));
			failure.put("metrics", Map.of("attempt", attempt, "modelCalls", 99));
			send(job, failure).andExpect(status().isOk()).andExpect(jsonPath("$.data.applied").value(false))
				.andExpect(jsonPath("$.data.reason").value("ATTEMPT_MISMATCH"));
			assertThat(processedEvents.existsById(id)).isFalse();
		}
		Job current = jobs.findById(job.getJobId()).orElseThrow();
		assertThat(current.getStatus()).isEqualTo(JobStatus.RUNNING);
		assertThat(current.getAttempt()).isEqualTo(2);
		assertThat(current.getMetrics()).isNull();
	}

	@Test
	void concurrentDeliveriesApplyTheEventExactlyOnce() throws Exception {
		Job job = newJob();
		start(job, 1);
		String id = "EVT-" + job.getJobId() + "-CONCURRENT-RETRY";
		var retry = event(job, id, 1, "RETRY_SCHEDULED", Map.of("nextAttempt", 2));
		CountDownLatch start = new CountDownLatch(1);
		try (var pool = Executors.newFixedThreadPool(8)) {
			List<Future<Boolean>> results = new ArrayList<>();
			for (int i = 0; i < 8; i++) {
				results.add(pool.submit(() -> {
					start.await();
					String response = send(job, retry).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
					return JsonPath.<Boolean>read(response, "$.data.applied");
				}));
			}
			start.countDown();
			int applied = 0;
			for (var result : results) if (result.get(20, TimeUnit.SECONDS)) applied++;
			assertThat(applied).isEqualTo(1);
		}
		assertThat(jobs.findById(job.getJobId()).orElseThrow().getAttempt()).isEqualTo(2);
		assertThat(jdbc.queryForObject("select count(*) from processed_agent_event where event_id = ?", Integer.class, id)).isEqualTo(1);
	}

	@Test
	void failedProcessingRollsBackTheReceiptSoTheSameIdCanBeRetried() throws Exception {
		Job job = newJob();
		String id = "EVT-" + job.getJobId() + "-INVALID-RETRY";
		send(job, event(job, id, 1, "RETRY_SCHEDULED", Map.of("nextAttempt", 1))).andExpect(status().isBadRequest());
		assertThat(processedEvents.existsById(id)).isFalse();
		assertThat(jobs.findById(job.getJobId()).orElseThrow().getStatus()).isEqualTo(JobStatus.QUEUED);
		send(job, event(job, id, 1, "RETRY_SCHEDULED", Map.of("nextAttempt", 2)))
			.andExpect(status().isOk()).andExpect(jsonPath("$.data.applied").value(true));
		assertThat(processedEvents.existsById(id)).isTrue();
		assertThat(jobs.findById(job.getJobId()).orElseThrow().getAttempt()).isEqualTo(2);
	}

	@Test
	void eventIdAndAttemptAreRequiredWithoutChangingTheJob() throws Exception {
		Job job = newJob();
		Map<String, Object> valid = event(job, "EVT-" + job.getJobId() + "-VALID", 1, "JOB_STARTED", Map.of());
		for (String missing : List.of("eventId", "attempt")) {
			var invalid = new java.util.LinkedHashMap<>(valid);
			invalid.remove(missing);
			send(job, invalid).andExpect(status().isBadRequest());
		}
		assertThat(jobs.findById(job.getJobId()).orElseThrow().getStatus()).isEqualTo(JobStatus.QUEUED);
		assertThat(processedEvents.existsById((String) valid.get("eventId"))).isFalse();
	}

	@Test
	void claimsRejectOtherAttemptsAndConcurrentWorkersCannotBothClaim() throws Exception {
		Job job = newJob();
		mvc.perform(post("/internal/jobs/{id}/claims", job.getJobId()).header("Authorization", BEARER)
			.contentType(MediaType.APPLICATION_JSON).content("{\"attempt\":2,\"workerId\":\"future-worker\"}"))
			.andExpect(status().isConflict());
		CountDownLatch start = new CountDownLatch(1);
		try (var pool = Executors.newFixedThreadPool(2)) {
			List<Future<Integer>> results = new ArrayList<>();
			for (int i = 0; i < 2; i++) {
				String worker = "worker-" + i;
				results.add(pool.submit(() -> {
					start.await();
					return mvc.perform(post("/internal/jobs/{id}/claims", job.getJobId()).header("Authorization", BEARER)
						.contentType(MediaType.APPLICATION_JSON).content(Json.write(Map.of("attempt", 1, "workerId", worker))))
						.andReturn().getResponse().getStatus();
				}));
			}
			start.countDown();
			assertThat(List.of(results.get(0).get(20, TimeUnit.SECONDS), results.get(1).get(20, TimeUnit.SECONDS)))
				.containsExactlyInAnyOrder(200, 409);
		}
	}

	private record ExternalIncident(String incidentId, String jobId) {}

	private ExternalIncident externalIncident() throws Exception {
		var accepted = ingest("""
			{"eventId":"EVT-DELIVERY-%s","anomalyType":"THICKNESS_OOC_R1","severity":"MEDIUM","processCode":"CMP",
			 "equipmentId":"CMP-PM3","lotId":"CURRENT-L009","waferIds":[],"runIds":[],"sensorIds":[],
			 "detectedAt":"2026-10-19T01:30:00Z"}
			""".formatted(UUID.randomUUID())).andExpect(status().isAccepted());
		return new ExternalIncident(json(accepted, "$.data.incidentId"), json(accepted, "$.data.jobId"));
	}

	@Test
	void repeatedManualBasicRunsStillUseAttemptOneForEachNewExternalJob() throws Exception {
		Session engineer = cmp();
		var created = externalIncident();
		String jobId = created.jobId();
		for (int run = 0; run < 5; run++) {
			Job current = jobs.findById(jobId).orElseThrow();
			assertThat(current.getAttempt()).isEqualTo(1);
			mvc.perform(post("/internal/jobs/{id}/claims", jobId).header("Authorization", BEARER)
				.contentType(MediaType.APPLICATION_JSON).content("{\"attempt\":1,\"workerId\":\"rerun-worker\"}"))
				.andExpect(status().isOk());
			send(current, event(current, "EVT-" + jobId + "-FAILED", 1, "JOB_FAILED",
				Map.of("errorCode", "TEST", "message", "manual rerun"))).andExpect(status().isOk());
			if (run < 4) {
				jobId = json(postAs(engineer, "/api/incidents/{id}/basic-analysis-jobs",
					"{\"expectedIncidentVersion\":" + incidentVersion(engineer, created.incidentId()) + "}", created.incidentId())
					.andExpect(status().isAccepted()), "$.data.jobId");
			}
		}
	}

	private Job newSummaryJob(Session engineer) throws Exception {
		var created = externalIncident();
		String incidentId = created.incidentId();
		Job basic = jobs.findById(created.jobId()).orElseThrow();
		send(basic, event(basic, "EVT-" + basic.getJobId() + "-FAILED", 1, "JOB_FAILED",
			Map.of("errorCode", "TEST_SETUP", "message", "end initial job"))).andExpect(status().isOk());
		String reportId = json(mvc.perform(post("/internal/incidents/{id}/report-drafts", incidentId)
			.header("Authorization", BEARER).contentType(MediaType.APPLICATION_JSON).content("""
			{"sections":{"overview":"개요","investigation":"조사","requestsAndReplies":"요청 없음",
			 "actionsAndVerification":"조치 없음","unresolved":"미해결 없음"},"evidenceIds":[]}
			""")).andExpect(status().isCreated()), "$.data.reportId");
		postAs(engineer, "/api/reports/{id}/confirmations", """
			{"expectedContentRevision":1,"reviewConfirmed":true,"classification":{"category":"METHOD"}}
			""", reportId).andExpect(status().isOk());
		return jobs.findByIncidentIdOrderByCreatedAtDesc(incidentId).stream()
			.filter(job -> job.getKind() == JobKind.SUMMARY).findFirst().orElseThrow();
	}

	@Test
	void lateSummaryFailureCannotFailTheNextAttemptAndValidSummaryStillCompletes() throws Exception {
		Session engineer = cmp();
		Job job = newSummaryJob(engineer);
		start(job, 1);
		send(job, event(job, "EVT-" + job.getJobId() + "-RETRY", 1, "RETRY_SCHEDULED", Map.of("nextAttempt", 2)))
			.andExpect(status().isOk());
		start(job, 2);
		send(job, event(job, "EVT-" + job.getJobId() + "-LATE-FAILURE", 1, "JOB_FAILED",
			Map.of("errorCode", "LATE", "message", "old failure")))
			.andExpect(status().isOk()).andExpect(jsonPath("$.data.applied").value(false));
		var ref = (Map<?, ?>) job.getInput().get("summaryRef");
		String reportId = (String) ref.get("reportId");
		getAs(engineer, "/api/reports/{id}", reportId).andExpect(jsonPath("$.data.summary.status").value("RUNNING"));
		String id = "EVT-" + job.getJobId() + "-READY";
		var ready = event(job, id, 2, "SUMMARY_READY", Map.of("reportId", reportId, "snapshotRef", ref.get("snapshotRef"),
			"summary", Map.of("lines", List.of("확정본 요약"), "evidenceIds", List.of(), "generatedBy", "rule")));
		send(job, ready).andExpect(status().isOk()).andExpect(jsonPath("$.data.applied").value(true));
		send(job, ready).andExpect(status().isOk()).andExpect(jsonPath("$.data.applied").value(false));
		getAs(engineer, "/api/reports/{id}", reportId).andExpect(jsonPath("$.data.summary.status").value("READY"));
		assertThat(jdbc.queryForObject("select count(*) from user_notification where type = 'REPORT_SUMMARY_FAILED' and incident_id = ?",
			Integer.class, job.getIncidentId())).isZero();
	}

	@Test
	void invalidSummaryRollsBackReceiptAndMetricsThenTheSameIdCanComplete() throws Exception {
		Session engineer = cmp();
		Job job = newSummaryJob(engineer);
		start(job, 1);
		var ref = (Map<?, ?>) job.getInput().get("summaryRef");
		String reportId = (String) ref.get("reportId");
		String id = "EVT-" + job.getJobId() + "-READY";
		var invalid = new java.util.LinkedHashMap<>(event(job, id, 1, "SUMMARY_READY",
			Map.of("reportId", reportId, "snapshotRef", ref.get("snapshotRef"),
				"summary", Map.of("lines", List.of("a", "b", "c"), "evidenceIds", List.of(), "generatedBy", "model"))));
		invalid.put("metrics", Map.of("attempt", 1, "modelCalls", 99));
		send(job, invalid).andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.error.hint").value("(INVALID_SUMMARY)"));
		assertThat(processedEvents.existsById(id)).isFalse();
		assertThat(jobs.findById(job.getJobId()).orElseThrow().getMetrics()).isNull();
		getAs(engineer, "/api/reports/{id}", reportId).andExpect(jsonPath("$.data.summary.status").value("RUNNING"));
		var valid = event(job, id, 1, "SUMMARY_READY", Map.of("reportId", reportId, "snapshotRef", ref.get("snapshotRef"),
			"summary", Map.of("lines", List.of("확정본 요약"), "evidenceIds", List.of(), "generatedBy", "model")));
		send(job, valid).andExpect(status().isOk()).andExpect(jsonPath("$.data.applied").value(true));
		assertThat(processedEvents.existsById(id)).isTrue();
		getAs(engineer, "/api/reports/{id}", reportId).andExpect(jsonPath("$.data.summary.status").value("READY"));
	}
    private Map<String, Object> result(Job job, String answer) {
        return Map.of("result", Map.of("answer", answer, "observedFacts", List.of(), "candidateCauses", List.of(),
                "evidenceRefs", List.of(), "limitations", List.of()), "analysis", Map.of("resultKind", "CAUSE_ANALYSIS"));
    }

    @Test
    void telemetryChangesDoNotConflictAndProgressEventsKeepTheirOwnIds() throws Exception {
        Job job = newJob();
        String id = "EVT-" + job.getJobId() + "-STARTED";
        var first = new java.util.LinkedHashMap<>(event(job, id, 1, "JOB_STARTED", Map.of("startedAt", "2026-10-23T00:00:00Z")));
        first.put("metrics", Map.of("modelCalls", 1));
        send(job, first).andExpect(jsonPath("$.data.applied").value(true));
        var repeated = new java.util.LinkedHashMap<>(event(job, id, 1, "JOB_STARTED", Map.of("startedAt", "2026-10-23T01:00:00Z")));
        repeated.put("sequence", 99); repeated.put("traceId", "another-trace"); repeated.put("metrics", Map.of("modelCalls", 99));
        send(job, repeated).andExpect(status().isOk()).andExpect(jsonPath("$.data.reason").value("DUPLICATE_EVENT"));
        assertThat(jobs.findById(job.getJobId()).orElseThrow().getMetrics()).containsEntry("modelCalls", 1);
        for (int n = 1; n <= 2; n++) send(job, event(job, "progress-" + job.getJobId() + "-" + n, 1,
                "ANALYSIS_PROGRESS", Map.of("stepCode", "CHECK", "message", "진행 " + n, "progressRatio", n * .2)))
                .andExpect(jsonPath("$.data.applied").value(true));
        assertThat(processedEvents.findById(id).orElseThrow().getConflictCount()).isNull();
    }

    @Test
    void changedSameIdIsRecordedWithoutOverwritingResultJobIncidentOrNotifications() throws Exception {
        Job job = newJob(); Session user = cmp(); String id = "ready-" + job.getJobId();
        send(job, event(job, id, 1, "RESULT_READY", result(job, "처음 보존한 답"))).andExpect(jsonPath("$.data.applied").value(true));
        long version = incidentVersion(user, job.getIncidentId());
        int notifications = jdbc.queryForObject("select count(*) from user_notification where incident_id = ?", Integer.class, job.getIncidentId());
        String resultRef = jobs.findById(job.getJobId()).orElseThrow().getResultRefId();
        String hash = processedEvents.findById(id).orElseThrow().getContentHash();
        send(job, event(job, id, 1, "RESULT_READY", result(job, "다른 답으로 덮어쓰기")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.applied").value(false))
                .andExpect(jsonPath("$.data.reason").value("EVENT_CONTENT_CONFLICT"));
        var receipt = processedEvents.findById(id).orElseThrow();
        assertThat(receipt.getContentHash()).isEqualTo(hash);
        assertThat(receipt.getConflictCount()).isEqualTo(1);
        assertThat(receipt.getLastConflictHash()).isNotEqualTo(hash);
        assertThat(receipt.getLastConflictEventId()).isEqualTo(id);
        assertThat(receipt.getLastConflictAt()).isNotNull();
        assertThat(jobs.findById(job.getJobId()).orElseThrow().getResultRefId()).isEqualTo(resultRef);
        assertThat(incidentVersion(user, job.getIncidentId())).isEqualTo(version);
        assertThat(jdbc.queryForObject("select count(*) from user_notification where incident_id = ?", Integer.class, job.getIncidentId())).isEqualTo(notifications);
        getAs(user, "/api/incidents/{id}/analysis-results", job.getIncidentId()).andExpect(jsonPath("$.data.answer").value("처음 보존한 답"));
    }

    @Test
    void changedEventIdCannotBypassLogicalFinalResultComparison() throws Exception {
        Job job = newJob(); String first = "first-" + job.getJobId();
        var payload = result(job, "보존한 답");
        send(job, event(job, first, 1, "RESULT_READY", payload)).andExpect(jsonPath("$.data.applied").value(true));
        send(job, event(job, "same-" + job.getJobId(), 1, "RESULT_READY", payload))
                .andExpect(jsonPath("$.data.reason").value("DUPLICATE_RESULT"));
        send(job, event(job, "different-" + job.getJobId(), 1, "RESULT_READY", result(job, "변경된 답")))
                .andExpect(jsonPath("$.data.reason").value("EVENT_CONTENT_CONFLICT"));
        send(job, event(job, "failed-" + job.getJobId(), 1, "JOB_FAILED", Map.of("errorCode", "LATE", "message", "늦은 실패")))
                .andExpect(jsonPath("$.data.reason").value("EVENT_CONTENT_CONFLICT"));
        assertThat(jobs.findById(job.getJobId()).orElseThrow().getStatus()).isEqualTo(JobStatus.SUCCEEDED);
        assertThat(processedEvents.findById(first).orElseThrow().getConflictCount()).isEqualTo(2);
        assertThat(jdbc.queryForObject("select count(*) from analysis_result where job_id = ?", Integer.class, job.getJobId())).isEqualTo(1);
    }

    @Test
    void concurrentDifferentAnswersWithDifferentIdsKeepExactlyOneCommittedWinner() throws Exception {
        Job job = newJob(); CountDownLatch gate = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(2)) {
            List<Future<String>> responses = new ArrayList<>();
            for (int n = 0; n < 2; n++) {
                int index = n;
                responses.add(pool.submit(() -> {
                    gate.await();
                    return send(job, event(job, "race-" + job.getJobId() + "-" + index, 1, "RESULT_READY", result(job, "답 " + index)))
                            .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
                }));
            }
            gate.countDown();
            List<String> texts = new ArrayList<>();
            for (var response : responses) texts.add(response.get(20, TimeUnit.SECONDS));
            assertThat(texts.stream().filter(t -> JsonPath.<Boolean>read(t, "$.data.applied")).count()).isEqualTo(1);
            assertThat(texts.stream().filter(t -> t.contains("EVENT_CONTENT_CONFLICT")).count()).isEqualTo(1);
        }
        var receipts = processedEvents.findByJobIdAndAttemptAndEventTypeIn(job.getJobId(), 1, List.of("RESULT_READY"));
        assertThat(receipts).hasSize(1);
        assertThat(receipts.getFirst().getConflictCount()).isEqualTo(1);
        assertThat(jdbc.queryForObject("select count(*) from analysis_result where job_id = ?", Integer.class, job.getJobId())).isEqualTo(1);
    }

    @Test
    void concurrentGlobalEventIdReuseAcrossJobsIsAConflictNotADatabaseError() throws Exception {
        Job left = newJob(), right = newJob(); String id = "global-" + UUID.randomUUID();
        CountDownLatch gate = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(2)) {
            List<Future<String>> responses = new ArrayList<>();
            for (Job job : List.of(left, right)) responses.add(pool.submit(() -> {
                gate.await();
                return send(job, event(job, id, 1, "JOB_STARTED", Map.of()))
                        .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
            }));
            gate.countDown(); List<String> texts = new ArrayList<>();
            for (var response : responses) texts.add(response.get(20, TimeUnit.SECONDS));
            assertThat(texts.stream().filter(t -> JsonPath.<Boolean>read(t, "$.data.applied")).count()).isEqualTo(1);
            assertThat(texts.stream().filter(t -> t.contains("EVENT_CONTENT_CONFLICT")).count()).isEqualTo(1);
        }
        assertThat(processedEvents.findById(id).orElseThrow().getConflictCount()).isEqualTo(1);
    }

    @Test
    void legacyReceiptIsNotBackfilledWithAnUnverifiedIncomingBody() throws Exception {
        Job job = newJob(); String id = "legacy-" + job.getJobId();
        processedEvents.save(new com.fabcontext.api.domain.job.ProcessedAgentEvent(id, job.getJobId(), 1, "JOB_STARTED", Instant.now()));
        send(job, event(job, id, 1, "JOB_STARTED", Map.of()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.reason").value("DUPLICATE_EVENT"));
        assertThat(processedEvents.findById(id).orElseThrow().getContentHash()).isNull();
        assertThat(jobs.findById(job.getJobId()).orElseThrow().getStatus()).isEqualTo(JobStatus.QUEUED);
    }

    @Test
    void summaryConflictDoesNotChangeTheConfirmedReportOrNotifyAgain() throws Exception {
        Session user = cmp(); Job job = newSummaryJob(user);
        var ref = (Map<?, ?>) job.getInput().get("summaryRef");
        String reportId = (String) ref.get("reportId"), id = "summary-" + job.getJobId();
        var payload = Map.<String, Object>of("reportId", reportId, "snapshotRef", ref.get("snapshotRef"),
                "summary", Map.of("lines", List.of("최초 확정 요약"), "evidenceIds", List.of(), "generatedBy", "rule"));
        send(job, event(job, id, 1, "SUMMARY_READY", payload)).andExpect(jsonPath("$.data.applied").value(true));
        var changed = new java.util.LinkedHashMap<>(payload);
        changed.put("summary", Map.of("lines", List.of("달라진 요약"), "evidenceIds", List.of(), "generatedBy", "rule"));
        send(job, event(job, "other-" + id, 1, "SUMMARY_READY", changed)).andExpect(jsonPath("$.data.reason").value("EVENT_CONTENT_CONFLICT"));
        getAs(user, "/api/reports/{id}", reportId).andExpect(jsonPath("$.data.summary.lines[0]").value("최초 확정 요약"))
                .andExpect(jsonPath("$.data.status").value("CONFIRMED"));
        assertThat(jdbc.queryForObject("select count(*) from user_notification where type = 'REPORT_SUMMARY_READY' and incident_id = ?",
                Integer.class, job.getIncidentId())).isEqualTo(1);
    }

    @Test
    void retryTimeChangeIsDuplicateButRetryDecisionChangesConflictEvenAfterAttemptAdvance() throws Exception {
        Job job = newJob(); String id = "retry-time-" + job.getJobId();
        send(job, event(job, id, 1, "RETRY_SCHEDULED", Map.of("nextAttempt", 2, "errorCode", "TRANSIENT", "nextRetryAt", "old")))
                .andExpect(jsonPath("$.data.applied").value(true));
        send(job, event(job, id, 1, "RETRY_SCHEDULED", Map.of("nextAttempt", 2, "errorCode", "TRANSIENT", "nextRetryAt", "new")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.reason").value("DUPLICATE_EVENT"));
        send(job, event(job, id, 1, "RETRY_SCHEDULED", Map.of("nextAttempt", 3, "errorCode", "TRANSIENT", "nextRetryAt", "new")))
                .andExpect(jsonPath("$.data.reason").value("EVENT_CONTENT_CONFLICT"));
        send(job, event(job, id, 1, "RETRY_SCHEDULED", Map.of("nextAttempt", 2, "errorCode", "OTHER", "nextRetryAt", "new")))
                .andExpect(jsonPath("$.data.reason").value("EVENT_CONTENT_CONFLICT"));
        assertThat(jobs.findById(job.getJobId()).orElseThrow().getAttempt()).isEqualTo(2);
        assertThat(processedEvents.findById(id).orElseThrow().getConflictCount()).isEqualTo(2);
    }

    @Test
    void oldRetryFingerprintIsNotComparedAsIfItUsedTheNewAlgorithmOrOverwritten() throws Exception {
        Job job = newJob(); String id = "old-retry-" + job.getJobId();
        var receipt = new com.fabcontext.api.domain.job.ProcessedAgentEvent(id, job.getJobId(), 1, "RETRY_SCHEDULED", Instant.now());
        String oldHash = com.fabcontext.api.common.Hashing.canonicalSha256(Map.of("nextRetryAt", "original"));
        receipt.fingerprint(oldHash); processedEvents.save(receipt);
        send(job, event(job, id, 1, "RETRY_SCHEDULED", Map.of("nextAttempt", 2, "errorCode", "TRANSIENT", "nextRetryAt", "new")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.reason").value("DUPLICATE_EVENT"));
        assertThat(processedEvents.findById(id).orElseThrow().getContentHash()).isEqualTo(oldHash);
        assertThat(processedEvents.findById(id).orElseThrow().getConflictCount()).isNull();
        assertThat(jobs.findById(job.getJobId()).orElseThrow().getAttempt()).isEqualTo(1);
    }

}
