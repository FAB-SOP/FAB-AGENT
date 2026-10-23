package com.fabcontext.api.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;

import com.fabcontext.api.common.Json;
import com.fabcontext.api.domain.analysis.AnalysisResultRepository;
import com.fabcontext.api.domain.approval.ApprovalRepository;
import com.fabcontext.api.domain.checkrequest.CheckRequestRepositories.CheckRequestRepository;
import com.fabcontext.api.domain.incident.IncidentRepository;
import com.fabcontext.api.domain.job.*;
import com.fabcontext.api.support.ApiTestSupport;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.support.TransactionTemplate;

@TestPropertySource(properties = {"fab.agent.mode=external", "fab.priority.worker-enabled=false",
        "spring.jpa.properties.hibernate.default_schema=backend_request_draft", "spring.datasource.hikari.schema=backend_request_draft"})
class RequestDraftApiTest extends ApiTestSupport {
    private static final String TOKEN = "Bearer dev-service-token";
    private static final String PATH = "/api/incidents/{id}/request-draft-jobs";
    @Autowired JobRepository jobs;
    @Autowired AnalysisResultRepository results;
    @Autowired ApprovalRepository approvals;
    @Autowired CheckRequestRepository requests;
    @Autowired IncidentRepository incidents;
    @Autowired TransactionTemplate tx;

    private ResultActions internal(String path, Map<String, Object> body, Object... args) throws Exception {
        return mvc.perform(post(path, args).header("Authorization", TOKEN).contentType(MediaType.APPLICATION_JSON).content(Json.write(body)));
    }
    private record Source(Job job, String resultId) {
        String incidentId() { return job.getIncidentId(); }
    }
    private Source source(String process, boolean withWafers) throws Exception {
        Map<String, Object> event = new LinkedHashMap<>(Map.of("eventId", "EVT-DRAFT-" + UUID.randomUUID(), "anomalyType", "THICKNESS_OOC_R1", "severity", "LOW",
                "processCode", process, "equipmentId", process + ("CMP".equals(process) ? "-PM3" : "-PM2"), "detectedAt", "2026-10-19T01:30:00Z"));
        if (withWafers) event.put("waferIds", List.of("W-CURRENT-009-3"));
        else event.put("runIds", List.of("RUN-W-CURRENT-009-3-" + process));
        String id = json(ingest(Json.write(event)).andExpect(status().isAccepted()), "$.data.jobId");
        Job job = jobs.findById(id).orElseThrow();
        Map<String, Object> cause = Map.of("candidateId", "candidate-etch", "causeIds", List.of("ETCH_WALL"), "hypothesis", "ETCH 정비 이력 확인",
                "stance", "UNDETERMINED", "priority", 1, "supportingEvidenceIds", List.of(), "conflictingEvidenceIds", List.of(), "nextCheck", "정비 기록 확인");
        internal("/internal/jobs/{id}/events", Map.of("eventId", "EVT-" + id, "jobId", id, "incidentId", job.getIncidentId(), "type", "RESULT_READY", "attempt", 1,
                "payload", Map.of("result", Map.of("answer", "앞 공정 이력을 추가 확인해야 합니다.", "candidateCauses", List.of(cause)))), id).andExpect(status().isOk());
        return new Source(job, results.findFirstByJobIdOrderByCreatedAtDesc(id).orElseThrow().getResultId());
    }
    private Source source() throws Exception { return source("CMP", true); }
    private Map<String, Object> body(Session user, Source source, String target) throws Exception {
        return new LinkedHashMap<>(Map.of("sourceResultId", source.resultId(), "targetProcessCode", target, "causeIds", List.of("ETCH_WALL"),
                "instruction", "정비 전후 시즈닝 기록을 확인하도록 작성해 주세요.", "expectedIncidentVersion", incidentVersion(user, source.incidentId())));
    }
    private String proposeAgent(Source source) throws Exception {
        return json(internal("/internal/jobs/{id}/proposals", Map.of("actionType", "CREATE_CHECK_REQUEST", "payload", Map.of("ownerUserId", "USR-ETCH-01",
                "targetProcessCode", "ETCH", "question", "정비 이력 확인"), "rationale", "기록 확인", "idempotencyKey", "draft-proposal-" + UUID.randomUUID()),
                source.job().getJobId()).andExpect(status().isCreated()), "$.data.approvalId");
    }

    @Test void createsRequestPurposeWithValidatedSourceAndDoesNotSendAnything() throws Exception {
        Session user = cmp(); Source source = source(); long approvalCount = approvals.count(), requestCount = requests.count();
        String id = json(postAs(user, PATH, Json.write(body(user, source, "ETCH")), source.incidentId()).andExpect(status().isAccepted())
                .andExpect(header().string("Location", org.hamcrest.Matchers.startsWith("/api/jobs/")))
                .andExpect(jsonPath("$.data.kind").value("DEEP")).andExpect(jsonPath("$.data.status").value("QUEUED")), "$.data.jobId");
        Job created = jobs.findById(id).orElseThrow();
        assertThat(created.getInput()).containsEntry("purpose", "REQUEST").containsEntry("sourceResultId", source.resultId());
        assertThat(created.getThreadId()).isNotEqualTo(source.job().getThreadId());
        assertThat(created.getParentJobId()).isNull();
        assertThat(created.getRequestedBy()).isEqualTo("USR-CMP-01");
        assertThat(created.getScope()).isEqualTo("UPSTREAM");
        assertThat(created.getQuestion()).contains(source.resultId(), "앞 공정 이력을 추가 확인", "정비 전후 시즈닝");
        mvc.perform(get("/internal/jobs/{id}/context", id).header("Authorization", TOKEN)).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.job.input.purpose").value("REQUEST"))
                .andExpect(jsonPath("$.data.job.input.request.targetProcessCode").value("ETCH"))
                .andExpect(jsonPath("$.data.job.input.request.causeIds[0]").value("ETCH_WALL"))
                .andExpect(jsonPath("$.data.job.input.sourceResultId").doesNotExist());
        assertThat(approvals.count()).isEqualTo(approvalCount); assertThat(requests.count()).isEqualTo(requestCount);
        getAs(user, "/api/incidents/{id}", source.incidentId()).andExpect(jsonPath("$.data.timeline[-1].type").value("REQUEST_DRAFT_REQUESTED"));
    }

    @Test void currentProcessAndOmittedCauseSelectionAreSupported() throws Exception {
        Session user = cmp(); Source source = source(); var body = body(user, source, "CMP"); body.remove("causeIds"); body.remove("instruction");
        String id = json(postAs(user, PATH, Json.write(body), source.incidentId()).andExpect(status().isAccepted()), "$.data.jobId");
        assertThat(jobs.findById(id).orElseThrow().getScope()).isEqualTo("CURRENT");
        assertThat(jobs.findById(id).orElseThrow().getInput().get("request")).isEqualTo(Map.of("targetProcessCode", "CMP", "causeIds", List.of()));
    }

    @Test void resolvesActualUpstreamFromRunWhenWaferListIsAbsent() throws Exception {
        Session user = cmp(); Source source = source("CMP", false);
        postAs(user, PATH, Json.write(body(user, source, "ETCH")), source.incidentId()).andExpect(status().isAccepted());
    }

    @Test void idempotentRetryReplaysTheOriginalJobAndDifferentBodyIsRejected() throws Exception {
        Session user = cmp(); Source source = source(); var body = body(user, source, "ETCH"); String key = UUID.randomUUID().toString();
        String job = json(postWithKey(user, key, PATH, Json.write(body), source.incidentId()).andExpect(status().isAccepted()), "$.data.jobId");
        postWithKey(user, key, PATH, Json.write(body), source.incidentId()).andExpect(status().isAccepted())
                .andExpect(header().string("Idempotent-Replayed", "true")).andExpect(jsonPath("$.data.jobId").value(job));
        body.put("instruction", "추가 내용");
        postWithKey(user, key, PATH, Json.write(body), source.incidentId()).andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error.code").value("IDEMPOTENCY_KEY_REUSED"));
        assertThat(jobs.findByIncidentIdOrderByCreatedAtDesc(source.incidentId())).hasSize(2);
    }

    @Test void pendingAgentCardIsPreservedAndNoNewJobIsCreated() throws Exception {
        Session user = cmp(); Source source = source(); String approval = proposeAgent(source);
        postAs(user, PATH, Json.write(body(user, source, "ETCH")), source.incidentId()).andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("AGENT_APPROVAL_PENDING"))
                .andExpect(jsonPath("$.error.details.approvalId").value(approval));
        assertThat(jobs.findByIncidentIdOrderByCreatedAtDesc(source.incidentId())).hasSize(1);
        getAs(user, "/api/approvals/{id}", approval).andExpect(jsonPath("$.data.status").value("PENDING"));
    }

    @Test void humanWorkApprovalDoesNotBlockRequestDraftCreation() throws Exception {
        Session user = cmp(); Source source = source();
        postAs(user, "/api/incidents/{id}/proposals", Json.write(Map.of("actionType", "CHECK_REQUEST", "payload", Map.of("ownerUserId", "USR-ETCH-01",
                "targetProcessCode", "ETCH", "question", "별도 확인"), "rationale", "별도 확인", "expectedIncidentVersion", incidentVersion(user, source.incidentId()))), source.incidentId())
                .andExpect(status().isCreated());
        postAs(user, PATH, Json.write(body(user, source, "ETCH")), source.incidentId()).andExpect(status().isAccepted());
    }

    @Test void sourceMustBelongToTheIncidentAndSelectedIdsMustBelongToTheSource() throws Exception {
        Session user = cmp(); Source source = source(); Source other = source(); var body = body(user, source, "ETCH");
        for (String invalid : List.of("res_missing", other.resultId())) {
            body.put("sourceResultId", invalid);
            postAs(user, PATH, Json.write(body), source.incidentId()).andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.error.code").value("REQUEST_SOURCE_INVALID"));
        }
        body.put("sourceResultId", source.resultId()); body.put("causeIds", List.of("CVD_GAS"));
        postAs(user, PATH, Json.write(body), source.incidentId()).andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.error.code").value("REQUEST_CAUSE_INVALID"));
        body.put("causeIds", List.of("ETCH_WALL", "ETCH_WALL"));
        postAs(user, PATH, Json.write(body), source.incidentId()).andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.error.code").value("DUPLICATE_CAUSE_ID"));
        assertThat(jobs.findByIncidentIdOrderByCreatedAtDesc(source.incidentId())).hasSize(1);
    }

    @Test void displayCauseIdCannotBeMistakenForTheMatrixId() throws Exception {
        Session user = cmp(); Source source = source();
        String displayId = String.valueOf(results.findById(source.resultId()).orElseThrow().getCandidateCauses().getFirst().get("causeId"));
        var body = body(user, source, "ETCH"); body.put("causeIds", List.of(displayId));
        postAs(user, PATH, Json.write(body), source.incidentId()).andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error.code").value("REQUEST_CAUSE_INVALID"));
    }

    @Test void legacyMatrixCauseIdsCanStillBeSelected() throws Exception {
        Session user = cmp(); Source source = source();
        tx.executeWithoutResult(s -> results.findById(source.resultId()).orElseThrow().getCandidateCauses().getFirst().remove("causeIds"));
        assertThat(results.findById(source.resultId()).orElseThrow().getCandidateCauses().getFirst()).doesNotContainKey("causeIds");
        postAs(user, PATH, Json.write(body(user, source, "ETCH")), source.incidentId()).andExpect(status().isAccepted());
    }

    @Test void downstreamAndUnrelatedProcessAreRejected() throws Exception {
        Session user = login("etch.lee"); Source source = source("ETCH", true);
        postAs(user, PATH, Json.write(body(user, source, "CMP")), source.incidentId()).andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.error.code").value("REQUEST_TARGET_INVALID"));
        tx.executeWithoutResult(s -> { var incident = incidents.findById(source.incidentId()).orElseThrow(); incident.getWaferIds().clear(); incident.getRunIds().clear(); });
        postAs(user, PATH, Json.write(body(user, source, "CVD")), source.incidentId()).andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.error.code").value("REQUEST_TARGET_INVALID"));
    }

    @Test void staleVersionRunningJobAndClosedIncidentAreBlocked() throws Exception {
        Session user = cmp(); Source source = source(); var body = body(user, source, "ETCH"); body.put("expectedIncidentVersion", 0);
        postAs(user, PATH, Json.write(body), source.incidentId()).andExpect(status().isConflict()).andExpect(jsonPath("$.error.code").value("VERSION_CONFLICT"));
        body = body(user, source, "ETCH");
        postAs(user, PATH, Json.write(body), source.incidentId()).andExpect(status().isAccepted());
        postAs(user, PATH, Json.write(body(user, source, "ETCH")), source.incidentId()).andExpect(status().isConflict()).andExpect(jsonPath("$.error.code").value("JOB_IN_PROGRESS"));
        Source closed = source(); tx.executeWithoutResult(s -> incidents.findById(closed.incidentId()).orElseThrow().closeByConfirmedReport(Instant.now(), "USR-CMP-01", "test-confirmed", 1));
        postAs(user, PATH, Json.write(body(user, closed, "ETCH")), closed.incidentId()).andExpect(status().isConflict()).andExpect(jsonPath("$.error.code").value("INCIDENT_CLOSED"));
    }

    @Test void concurrentClicksWithDifferentKeysCreateOnlyOneJob() throws Exception {
        Session user = cmp(); Source source = source(); String json = Json.write(body(user, source, "ETCH")); CountDownLatch go = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(2)) {
            var first = pool.submit(() -> { go.await(); return postAs(user, PATH, json, source.incidentId()).andReturn().getResponse().getStatus(); });
            var second = pool.submit(() -> { go.await(); return postAs(user, PATH, json, source.incidentId()).andReturn().getResponse().getStatus(); });
            go.countDown(); assertThat(List.of(first.get(20, TimeUnit.SECONDS), second.get(20, TimeUnit.SECONDS))).containsExactlyInAnyOrder(202, 409);
        }
        assertThat(jobs.findByIncidentIdOrderByCreatedAtDesc(source.incidentId())).hasSize(2);
    }

    @Test void permissionsValidationAndSwaggerAreExposed() throws Exception {
        Session owner = cmp(); Source source = source(); String json = Json.write(body(owner, source, "ETCH"));
        postAs(login("mnt.jung"), PATH, json, source.incidentId()).andExpect(status().isForbidden()).andExpect(jsonPath("$.error.code").value("INVESTIGATION_WRITE_DENIED"));
        postAs(cvd(), PATH, json, source.incidentId()).andExpect(status().isForbidden());
        var anonymous = new org.springframework.mock.web.MockHttpSession();
        String csrf = json(mvc.perform(get("/api/auth/csrf").session(anonymous)), "$.data.token");
        mvc.perform(post(PATH, source.incidentId()).session(anonymous).header("X-CSRF-TOKEN", csrf)
                .header("Idempotency-Key", UUID.randomUUID().toString()).contentType(MediaType.APPLICATION_JSON).content(json)).andExpect(status().isUnauthorized());
        mvc.perform(post(PATH, source.incidentId()).session(owner.http()).header("Idempotency-Key", UUID.randomUUID().toString()).contentType(MediaType.APPLICATION_JSON).content(json)).andExpect(status().isForbidden());
        var invalid = body(owner, source, "ETCH"); invalid.put("targetProcessCode", "UNKNOWN");
        postAs(owner, PATH, Json.write(invalid), source.incidentId()).andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"));
        var doc = new tools.jackson.databind.json.JsonMapper().readTree(mvc.perform(get("/v3/api-docs")).andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertThat(doc.at("/paths/~1api~1incidents~1{incidentId}~1request-draft-jobs/post/responses/202/content/application~1json/schema/$ref").asText()).endsWith("/RequestDraftAcceptedEnvelope");
        assertThat(doc.at("/components/schemas/RequestDraftJobInput/required").toString()).contains("sourceResultId", "targetProcessCode", "expectedIncidentVersion");
    }
}
