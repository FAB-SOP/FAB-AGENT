package com.fabcontext.api.api;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.doCallRealMethod;
import static org.mockito.Mockito.doThrow;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.fabcontext.api.common.Json;
import com.fabcontext.api.domain.events.AnomalyEvent;
import com.fabcontext.api.domain.events.EventService;
import com.fabcontext.api.domain.incident.IncidentRepository;
import com.fabcontext.api.domain.job.JobRepository;
import com.fabcontext.api.domain.priority.*;
import com.fabcontext.api.support.ApiTestSupport;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;

/** AI가 꺼진 상태에서도 우선순위는 독립 실행. 작업 스케줄러 대신 tick을 직접 호출해 경합·재시도를 결정적으로 검증. */
@TestPropertySource(properties = {"fab.priority.worker-enabled=false", "fab.agent.mode=external",
        "spring.jpa.properties.hibernate.default_schema=backend_priority", "spring.datasource.hikari.schema=backend_priority"})
class PriorityEvaluationApiTest extends ApiTestSupport {
    @Autowired PriorityEvaluationRepository evaluations;
    @Autowired PriorityEvaluationService service;
    @MockitoSpyBean PriorityEvaluationCalculator calculator;
    @Autowired IncidentRepository incidents;
    @Autowired JobRepository jobs;
    @Autowired EventService events;
    @Autowired JdbcTemplate jdbc;
    @Autowired TransactionTemplate tx;
    @Autowired PlatformTransactionManager transactions;
    private final List<String> ids = new ArrayList<>();

    private String body(String event, String run, String equipment, String wafer) {
        return Json.write(Map.of("eventId", event, "anomalyType", "THICKNESS_OOC_R1", "severity", "LOW",
                "processCode", "CMP", "equipmentId", equipment, "lotId", "CURRENT-L009",
                "waferIds", wafer == null ? List.of() : List.of(wafer), "runIds", run == null ? List.of() : List.of(run),
                "detectedAt", "2026-10-19T01:30:00Z"));
    }
    private String create(String body) throws Exception {
        String id = json(ingest(body).andExpect(status().isAccepted()), "$.data.incidentId");
        ids.add(id);
        return id;
    }
    private String create() throws Exception {
        return create(body("EVT-PRIORITY-" + UUID.randomUUID(), "RUN-W-CURRENT-009-3-CMP", "CMP-PM3", "W-CURRENT-009-3"));
    }
    private void tick() { new PriorityEvaluationWorker(service, calculator).tick(); }

    @AfterEach void cleanup() { ids.forEach(evaluations::deleteById); }

    @Test void independentPriorityCompletesAndPublishesWithoutChangingQueuedAiOrIncidentState() throws Exception {
        String id = create();
        var before = incidents.findById(id).orElseThrow();
        getAs(cmp(), "/api/incidents/{id}", id).andExpect(jsonPath("$.data.investigationPriority.status").value("PENDING"));
        var stream = mvc.perform(get("/api/incidents/{id}/stream", id).session(cmp().http()))
                .andExpect(request().asyncStarted()).andReturn();
        try {
            tick();
            getAs(cmp(), "/api/incidents/{id}", id).andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.investigationPriority.status").value("COMPLETED"))
                    .andExpect(jsonPath("$.data.investigationPriority.level").value("HIGH"))
                    .andExpect(jsonPath("$.data.severity").value("LOW"));
            var after = incidents.findById(id).orElseThrow();
            assertThat(after.getActiveJobId()).isEqualTo(before.getActiveJobId());
            assertThat(after.getLatestJobStatus()).isEqualTo(before.getLatestJobStatus());
            assertThat(after.getStatus()).isEqualTo(before.getStatus());
            assertThat(after.getVersion()).isEqualTo(before.getVersion());
            assertThat(jobs.findById(after.getActiveJobId()).orElseThrow().getStatus().name()).isEqualTo("QUEUED");
            var saved = evaluations.findById(id).orElseThrow();
            assertThat(saved.getStatus()).isEqualTo(PriorityEvaluation.Status.COMPLETED);
            assertThat(saved.getDetails()).containsKeys("policyVersion", "assessmentAsOf", "runAssessments");
            assertThat(stream.getResponse().getContentAsString()).contains("event:incident.updated", "COMPLETED", "HIGH");
            getAs(cvd(), "/api/incidents/{id}", id).andExpect(status().isForbidden());
        } finally { stream.getRequest().getAsyncContext().complete(); }
    }

    @Test void duplicateIngestNeverCreatesAnotherEvaluationOrRecomputesCompletedValue() throws Exception {
        String body = body("EVT-PRIORITY-" + UUID.randomUUID(), "RUN-W-CURRENT-009-3-CMP", "CMP-PM3", "W-CURRENT-009-3");
        String id = create(body);
        tick();
        long version = incidentVersion(cmp(), id);
        Instant finished = evaluations.findById(id).orElseThrow().getFinishedAt();
        ingest(body).andExpect(status().isOk()).andExpect(jsonPath("$.data.incidentId").value(id));
        tick();
        assertThat(evaluations.findById(id).orElseThrow().getAttempt()).isEqualTo(1);
        assertThat(evaluations.findById(id).orElseThrow().getFinishedAt()).isEqualTo(finished);
        assertThat(incidentVersion(cmp(), id)).isEqualTo(version);
    }

    @Test void rollbackKeepsNeitherIncidentNorEvaluationAndSchedulerCannotSeeIt() {
        long count = evaluations.count();
        String event = "EVT-ROLLBACK-" + UUID.randomUUID();
        assertThatThrownBy(() -> tx.executeWithoutResult(status -> {
            events.ingest(Json.convert(Json.tree(Json.read(body(event, "RUN-W-CURRENT-009-3-CMP", "CMP-PM3", "W-CURRENT-009-3"),
                    new tools.jackson.core.type.TypeReference<Map<String,Object>>() {})), AnomalyEvent.class));
            throw new IllegalStateException("rollback fixture");
        })).isInstanceOf(IllegalStateException.class);
        assertThat(incidents.findBySourceEventId(event)).isEmpty();
        assertThat(evaluations.count()).isEqualTo(count);
    }

    @Test void missingTargetOrWrongEquipmentIsFailedNotLowOrMediumAndAiStaysQueued() throws Exception {
        for (String body : List.of(body("EVT-PRIORITY-" + UUID.randomUUID(), null, "CMP-PM3", null),
                body("EVT-PRIORITY-" + UUID.randomUUID(), "RUN-W-CURRENT-009-3-CMP", "CMP-PM2", "W-CURRENT-009-3"))) {
            String id = create(body);
            tick();
            getAs(cmp(), "/api/incidents/{id}", id)
                    .andExpect(jsonPath("$.data.investigationPriority.status").value("FAILED"))
                    .andExpect(jsonPath("$.data.investigationPriority.level").isEmpty())
                    .andExpect(jsonPath("$.data.investigationPriority.provisional").value(false))
                    .andExpect(jsonPath("$.data.activeJob.status").value("QUEUED"));
        }
    }

    @Test void resolvesEventWaferLineageWhenRunListIsMissing() throws Exception {
        String id = create(body("EVT-PRIORITY-" + UUID.randomUUID(), null, "CMP-PM3", "W-CURRENT-009-3"));
        tick();
        assertThat(incidents.findById(id).orElseThrow().getInvestigationPriority().getLevel().name()).isEqualTo("HIGH");
    }

    @Test void transientFailureRetriesPersistedInputWithoutStartingAnotherAiJob() throws Exception {
        String id = create();
        doThrow(new DataAccessResourceFailureException("fixture connection loss")).when(calculator)
                .calculate(argThat(input -> id.equals(input.incidentId())));
        tick();
        var first = evaluations.findById(id).orElseThrow();
        assertThat(first.getStatus()).isEqualTo(PriorityEvaluation.Status.PENDING);
        assertThat(first.getAttempt()).isEqualTo(1);
        doCallRealMethod().when(calculator).calculate(argThat(input -> id.equals(input.incidentId())));
        jdbc.update("update priority_evaluation set next_attempt_at=? where incident_id=?", Timestamp.from(Instant.EPOCH), id);
        tick();
        var done = evaluations.findById(id).orElseThrow();
        assertThat(done.getStatus()).isEqualTo(PriorityEvaluation.Status.COMPLETED);
        assertThat(done.getAttempt()).isEqualTo(2);
        assertThat(done.getAssessmentAsOf()).isEqualTo(first.getAssessmentAsOf());
        assertThat(jobs.findByIncidentIdOrderByCreatedAtDesc(id)).hasSize(1);
    }

    @Test void expiredClaimIsRecoverableButLatePreviousCompletionCannotOverwriteCurrentAttempt() throws Exception {
        String id = create();
        var old = service.claimNext().orElseThrow();
        assertThat(service.claimNext()).isEmpty();
        jdbc.update("update priority_evaluation set lease_until=? where incident_id=?", Timestamp.from(Instant.EPOCH), id);
        var current = service.claimNext().orElseThrow();
        assertThat(current.attempt()).isEqualTo(2);
        service.complete(old, calculator.calculate(old));
        assertThat(evaluations.findById(id).orElseThrow().getStatus()).isEqualTo(PriorityEvaluation.Status.RUNNING);
        service.complete(current, calculator.calculate(current));
        assertThat(evaluations.findById(id).orElseThrow().getStatus()).isEqualTo(PriorityEvaluation.Status.COMPLETED);
    }

    @Test void exhaustedExpiredAttemptBecomesVisibleFailureInsteadOfRemainingPendingForever() throws Exception {
        String id = create();
        service.claimNext().orElseThrow();
        jdbc.update("update priority_evaluation set attempt=3, lease_until=? where incident_id=?", Timestamp.from(Instant.EPOCH), id);
        assertThat(service.claimNext()).isEmpty();
        assertThat(evaluations.findById(id).orElseThrow().getStatus()).isEqualTo(PriorityEvaluation.Status.FAILED);
        getAs(cmp(), "/api/incidents/{id}", id).andExpect(jsonPath("$.data.investigationPriority.status").value("FAILED"));
    }

    @Test void aiWriteFromOlderIncidentSnapshotNeitherFailsNorOverwritesCompletedPriority() throws Exception {
        String id = create();
        var input = service.claimNext().orElseThrow();
        var outcome = calculator.calculate(input);
        long version = incidentVersion(cmp(), id);
        TransactionTemplate independent = new TransactionTemplate(transactions);
        independent.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        tx.executeWithoutResult(status -> {
            var oldAiSnapshot = incidents.findById(id).orElseThrow();
            independent.executeWithoutResult(other -> service.complete(input, outcome));
            oldAiSnapshot.record(Instant.now(), "TEST_AI_UPDATE", "agent", "동시 조사 갱신", "JOB", "fixture");
        });
        var after = incidents.findById(id).orElseThrow();
        assertThat(after.getInvestigationPriority().getLevel().name()).isEqualTo("HIGH");
        assertThat(after.getVersion()).isEqualTo(version + 1); // 조사 기록만 버전을 올린다
    }
}
