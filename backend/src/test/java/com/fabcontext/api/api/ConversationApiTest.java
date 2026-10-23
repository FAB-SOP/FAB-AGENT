package com.fabcontext.api.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import java.time.Instant;
import java.util.*;

import com.fabcontext.api.common.Json;
import com.fabcontext.api.domain.action.IncidentAction;
import com.fabcontext.api.domain.action.IncidentActionRepository;
import com.fabcontext.api.domain.analysis.AnalysisResult;
import com.fabcontext.api.domain.analysis.AnalysisResultRepository;
import com.fabcontext.api.domain.approval.*;
import com.fabcontext.api.domain.checkrequest.CheckRequestRepositories.*;
import com.fabcontext.api.domain.incident.IncidentRepository;
import com.fabcontext.api.domain.job.*;
import com.fabcontext.api.support.ApiTestSupport;
import jakarta.persistence.EntityManagerFactory;
import org.hibernate.SessionFactory;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;

@TestPropertySource(properties = {"fab.agent.mode=external", "fab.priority.worker-enabled=false",
        "spring.jpa.properties.hibernate.generate_statistics=true", "spring.jpa.properties.hibernate.session.events.log=false",
        "logging.level.org.hibernate.stat=OFF", "logging.level.org.hibernate.engine.internal.StatisticalLoggingSessionEventListener=OFF",
        "spring.jpa.properties.hibernate.default_schema=backend_conversation", "spring.datasource.hikari.schema=backend_conversation"})
class ConversationApiTest extends ApiTestSupport {
    private static final String TOKEN = "Bearer dev-service-token";
    private static final String PATH = "/api/incidents/{id}/conversation";
    @Autowired JobRepository jobs;
    @Autowired AnalysisResultRepository results;
    @Autowired ApprovalRepository approvals;
    @Autowired CheckRequestRepository requests;
    @Autowired ReplyRepository replies;
    @Autowired IncidentActionRepository actions;
    @Autowired IncidentRepository incidents;
    @Autowired TransactionTemplate tx;
    @Autowired EntityManagerFactory emf;
    private ResultActions internal(String path, Map<String, Object> body, Object... args) throws Exception {
        return mvc.perform(post(path, args).header("Authorization", TOKEN).contentType(MediaType.APPLICATION_JSON).content(Json.write(body)));
    }
    private ResultActions event(String jobId, String type, Map<String, Object> payload) throws Exception {
        Job job = jobs.findById(jobId).orElseThrow();
        return internal("/internal/jobs/{id}/events", Map.of("eventId", "EVT-" + jobId + "-" + type, "jobId", jobId, "incidentId", job.getIncidentId(),
                "type", type, "attempt", 1, "payload", payload), jobId);
    }
    private String finish(String jobId, String text, String evidenceId) throws Exception {
        Map<String, Object> result = new LinkedHashMap<>(Map.of("answer", text, "candidateCauses", List.of(), "limitations", List.of("확정 아님")));
        if (evidenceId != null) { result.put("observedFacts", List.of(Map.of("text", text, "evidenceIds", List.of(evidenceId))));
            result.put("evidenceRefs", List.of(Map.of("evidenceId", evidenceId, "sourceType", evidenceId.startsWith("E-ACT-") ? "ACTION" : "REPLY", "sourceId", "statement"))); }
        event(jobId, "RESULT_READY", Map.of("result", result)).andExpect(status().isOk());
        return results.findFirstByJobIdOrderByCreatedAtDesc(jobId).orElseThrow().getResultId();
    }
    private record CaseIncident(String incidentId, String jobId) {}
    private CaseIncident base(boolean finish) throws Exception {
        var accepted = ingest(Json.write(Map.of("eventId", "EVT-CONV-" + UUID.randomUUID(), "anomalyType", "THICKNESS_OOC_R1", "severity", "LOW", "processCode", "CMP",
                "equipmentId", "CMP-PM3", "detectedAt", "2026-10-19T01:30:00Z", "waferIds", List.of("W-CURRENT-009-3")))).andExpect(status().isAccepted());
        var incident = new CaseIncident(json(accepted, "$.data.incidentId"), json(accepted, "$.data.jobId"));
        if (finish) finish(incident.jobId(), "기본 원인 분석", null);
        return incident;
    }
    private String ask(Session user, String incident, String question) throws Exception {
        return json(postAs(user, "/api/incidents/{id}/questions", Json.write(Map.of("question", question, "expectedIncidentVersion", incidentVersion(user, incident))), incident)
                .andExpect(status().isAccepted()), "$.data.jobId");
    }
    private JsonNode data(Session user, String incident) throws Exception {
        return new tools.jackson.databind.json.JsonMapper().readTree(getAs(user, PATH + "?size=100", incident).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8)).get("data");
    }
    private List<JsonNode> entries(JsonNode data) {
        List<JsonNode> entries = new ArrayList<>(); data.get("items").forEach(entries::add); return entries;
    }
    private JsonNode one(JsonNode data, String type) { return entries(data).stream().filter(e -> type.equals(e.get("type").asText())).findFirst().orElseThrow(); }
    private Job rawJob(String incident, JobKind kind, String thread, String parent, Instant time, Map<String, Object> input) {
        return jobs.save(Job.builder().jobId("job_test_" + UUID.randomUUID()).incidentId(incident).kind(kind).attempt(1).threadId(thread).parentJobId(parent)
                .question("반복 질문").requestedBy("USR-CMP-01").input(input).createdAt(time).build());
    }

    @Test void queuedBasicHasAConversationBeforeAnyAnswerExists() throws Exception {
        Session user = cmp(); var incident = base(false); JsonNode data = data(user, incident.incidentId());
        assertThat(entries(data)).hasSize(1); assertThat(one(data, "BASIC_REQUESTED").path("job").path("status").asText()).isEqualTo("QUEUED");
        assertThat(data.path("runningJobs").size()).isEqualTo(1); assertThat(data.get("latestCauseAnalysis").isNull()).isTrue();
    }

    @Test void answersQuestionsAndResultsAreSelfContainedAndReadOnly() throws Exception {
        Session user = cmp(); var incident = base(true); String question = ask(user, incident.incidentId(), "패드 상태는 어떤가요?"); String answer = finish(question, "질문 답변", null);
        long version = incidentVersion(user, incident.incidentId()); long count = jobs.count();
        JsonNode data = data(user, incident.incidentId()); JsonNode q = one(data, "QUESTION");
        JsonNode a = entries(data).stream().filter(e -> e.path("links").path("resultId").asText().equals(answer)).findFirst().orElseThrow();
        assertThat(q.path("text").asText()).isEqualTo("패드 상태는 어떤가요?");
        assertThat(q.path("actor").path("type").asText()).isEqualTo("HUMAN");
        assertThat(a.path("actor").path("type").asText()).isEqualTo("AI");
        assertThat(a.path("links").path("parentEntryId").asText()).isEqualTo(q.path("entryId").asText());
        assertThat(a.path("data").path("analysis").path("answer").asText()).isEqualTo("질문 답변");
        assertThat(data.path("latestCauseAnalysis").path("jobId").asText()).isEqualTo(incident.jobId());
        JsonNode repeated = data(user, incident.incidentId());
        assertThat(entries(repeated).stream().map(e -> e.path("entryId").asText()).toList()).isEqualTo(entries(data).stream().map(e -> e.path("entryId").asText()).toList());
        assertThat(incidentVersion(user, incident.incidentId())).isEqualTo(version); assertThat(jobs.count()).isEqualTo(count);
    }

    private record RequestFlow(CaseIncident incident, String question, String approval, String resume, String request) {}
    private RequestFlow requestFlow(Session user) throws Exception {
        return requestFlow(user, "정비 이력 확인");
    }
    private RequestFlow requestFlow(Session user, String requestQuestion) throws Exception {
        var incident = base(true); String question = ask(user, incident.incidentId(), "앞 공정 확인 요청");
        String approval = json(internal("/internal/jobs/{id}/proposals", Map.of("actionType", "CREATE_CHECK_REQUEST", "payload", Map.of("ownerUserId", "USR-ETCH-01", "targetProcessCode", "ETCH", "question", requestQuestion),
                "rationale", "원인 확인 필요", "idempotencyKey", "proposal-conv-" + question), question).andExpect(status().isCreated()), "$.data.approvalId");
        event(question, "APPROVAL_REQUIRED", Map.of("approvalId", approval, "approvalVersion", 1, "interruptId", "INT-private",
                "result", Map.of("answer", "승인 전에 확인 가능한 분석 답변"))).andExpect(status().isOk());
        String resume = json(postAs(user, "/api/approvals/{id}/decisions", "{\"decision\":\"APPROVE\",\"expectedVersion\":1}", approval).andExpect(status().isOk()), "$.data.resumeJobId");
        String request = json(internal("/internal/approved-actions/check-request", Map.of("approvalId", approval, "idempotencyKey", "send-conv-" + approval))
                .andExpect(status().isOk()), "$.data.data.requestId");
        event(resume, "WAITING_REPLY", Map.of("requestId", request, "interruptId", "INT-REPLY-private")).andExpect(status().isOk());
        return new RequestFlow(incident, question, approval, resume, request);
    }

    @Test void lateReplyCarriesItsOriginalBlockAndRequestContextWithoutLoadingPreviousPages() throws Exception {
        Session user = cmp(); var flow = requestFlow(user, "정비 기록 " + "🤖".repeat(220));
        String later = ask(user, flow.incident().incidentId(), "그 뒤 별도 질문"); finish(later, "뒤 질문 답변", null);
        Session receiver = login("etch.lee"); acceptRequest(receiver, flow.request());
        postAs(receiver, "/api/check-requests/{id}/replies", "{\"findings\":\"원 요청의 늦은 회신\",\"confirmedByUser\":true,\"expectedVersion\":2}", flow.request()).andExpect(status().isCreated());
        long version = incidentVersion(user, flow.incident().incidentId()), count = jobs.count();
        JsonNode page = new tools.jackson.databind.json.JsonMapper().readTree(getAs(user, PATH + "?size=1", flow.incident().incidentId())
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8)).get("data");
        JsonNode reply = one(page, "REPLY");
        assertThat(entries(page)).hasSize(1);
        assertThat(reply.path("requestSummary").path("targetProcessCode").asText()).isEqualTo("ETCH");
        String summary = reply.path("requestSummary").path("question").asText();
        assertThat(summary.codePointCount(0, summary.length())).isEqualTo(200); assertThat(summary).endsWith("…");
        assertThat(reply.path("requestSummary").size()).isEqualTo(3); // 원문 brief·분석을 복사하지 않는 최소 요약
        assertThat(reply.path("placement").path("blockId").asText()).isEqualTo("job:" + flow.question() + ":requested");
        assertThat(reply.path("placement").path("title").asText()).isEqualTo("앞 공정 확인 요청");
        assertThat(reply.path("placement").path("flowId").asText()).isEqualTo("approval:" + flow.approval());
        assertThat(reply.path("links").path("approvalId").asText()).isEqualTo(flow.approval());
        assertThat(reply.path("links").path("sourceResultId").asText()).isNotBlank();
        assertThat(incidentVersion(user, flow.incident().incidentId())).isEqualTo(version); assertThat(jobs.count()).isEqualTo(count);
    }

    private String proposeWork(Session user, CaseIncident incident, String operation) throws Exception {
        String result = results.findFirstByJobIdOrderByCreatedAtDesc(incident.jobId()).orElseThrow().getResultId();
        return json(postAs(user, "/api/incidents/{id}/work-items", Json.write(Map.of("operationCode", operation, "title", "상태 확인", "reason", "추가 확인 필요",
                "sourceResultId", result, "expectedIncidentVersion", incidentVersion(user, incident.incidentId()))), incident.incidentId()).andExpect(status().isCreated()), "$.data.taskId");
    }

    @Test void workRequestApprovalAndReplyShareTheSourceAnswerFlow() throws Exception {
        Session user = cmp(); var incident = base(true); String task = proposeWork(user, incident, "UPSTREAM_CHECK");
        String request = json(postAs(user, "/api/work-items/{id}/adoptions", "{\"expectedVersion\":1,\"ownerUserId\":\"USR-ETCH-01\",\"targetProcessCode\":\"ETCH\"}", task)
                .andExpect(status().isOk()), "$.data.requestId");
        Session receiver = login("etch.lee"); acceptRequest(receiver, request);
        postAs(receiver, "/api/check-requests/{id}/replies", "{\"findings\":\"점검 완료\",\"confirmedByUser\":true,\"expectedVersion\":2}", request).andExpect(status().isCreated());
        JsonNode data = data(user, incident.incidentId());
        String source = results.findFirstByJobIdOrderByCreatedAtDesc(incident.jobId()).orElseThrow().getResultId();
        for (String type : List.of("WORK_PROPOSED", "WORK_EVENT", "APPROVAL_REQUESTED", "APPROVAL_DECIDED", "REQUEST_SENT", "REQUEST_ACCEPTED", "REPLY")) {
            JsonNode entry = one(data, type);
            assertThat(entry.path("placement").path("blockId").asText()).as(type).isEqualTo("job:" + incident.jobId() + ":requested");
            assertThat(entry.path("placement").path("flowId").asText()).as(type).isEqualTo("work:" + task);
            assertThat(entry.path("links").path("taskId").asText()).as(type).isEqualTo(task);
            assertThat(entry.path("links").path("sourceResultId").asText()).as(type).isEqualTo(source);
        }
        assertThat(one(data, "APPROVAL_REQUESTED").path("links").path("parentEntryId").asText()).isEqualTo("work:" + task + ":proposed");
    }

    @Test void directWorkCompletionStaysUnderTheAdoptedSuggestion() throws Exception {
        Session user = cmp(); var incident = base(true); String task = proposeWork(user, incident, "REVIEW_SENSOR_DATA");
        postAs(user, "/api/work-items/{id}/adoptions", "{\"expectedVersion\":1}", task).andExpect(status().isOk());
        postAs(user, "/api/work-items/{id}/completions", Json.write(Map.of("description", "설정값과 실측값 일치 확인", "performedAt", "2026-10-22T00:00:00Z",
                "confirmedByUser", true, "expectedVersion", 2, "expectedIncidentVersion", incidentVersion(user, incident.incidentId()))), task).andExpect(status().isOk());
        JsonNode data = data(user, incident.incidentId());
        assertThat(one(data, "ACTION_RESULT").path("placement")).isEqualTo(one(data, "WORK_PROPOSED").path("placement"));
        assertThat(one(data, "ACTION_RESULT").path("links").path("parentEntryId").asText()).isEqualTo("work:" + task + ":proposed");
    }

    @Test void requestButtonUsesItsBasisBlockWhileUnrelatedQuestionsStaySeparate() throws Exception {
        Session user = cmp(); var incident = base(true);
        String source = results.findFirstByJobIdOrderByCreatedAtDesc(incident.jobId()).orElseThrow().getResultId();
        Job draft = tx.execute(s -> rawJob(incident.incidentId(), JobKind.DEEP, "draft", null, Instant.now(), Map.of("purpose", "REQUEST", "sourceResultId", source)));
        JsonNode entry = one(data(user, incident.incidentId()), "REQUEST_DRAFT_REQUESTED");
        assertThat(entry.path("links").path("jobId").asText()).isEqualTo(draft.getJobId());
        assertThat(entry.path("placement").path("blockId").asText()).isEqualTo("job:" + incident.jobId() + ":requested");
    }

    @Test void manualApprovalHasItsOwnDirectBlockWithoutInventingAnAnalysis() throws Exception {
        Session user = cmp(); var incident = base(true);
        Approval manual = approvals.save(Approval.builder().approvalId("apv_" + UUID.randomUUID()).incidentId(incident.incidentId()).processCode("CMP")
                .actionType(ApprovalActionType.CHECK_REQUEST).payload(Map.of("question", "직접 확인 요청")).rationale("직접 판단").source("USER").createdBy("USR-CMP-01")
                .createdAt(Instant.now()).expiresAt(Instant.now().plusSeconds(3600)).build());
        JsonNode entry = one(data(user, incident.incidentId()), "APPROVAL_REQUESTED");
        assertThat(entry.path("placement").path("blockType").asText()).isEqualTo("DIRECT");
        assertThat(entry.path("placement").path("blockId").asText()).isEqualTo("approval:" + manual.getApprovalId());
        assertThat(entry.path("links").get("sourceResultId").isNull()).isTrue();
    }

    @Test void approvalReplyAndSelectedFollowupStayConnectedAcrossResumeJobs() throws Exception {
        Session user = cmp(); var flow = requestFlow(user); Session receiver = login("etch.lee");
        acceptRequest(receiver, flow.request());
        String reply = json(postAs(receiver, "/api/check-requests/{id}/replies", "{\"findings\":\"시즈닝 기록 확인 완료\",\"confirmedByUser\":true,\"expectedVersion\":2}", flow.request())
                .andExpect(status().isCreated()), "$.data.replyId");
        JsonNode before = data(user, flow.incident().incidentId()); assertThat(jobs.findById(flow.resume()).orElseThrow().getStatus()).isEqualTo(JobStatus.WAITING_REPLY);
        var accepted = postAs(user, "/api/incidents/{id}/followup-analysis-jobs", Json.write(Map.of("sourceRefs", List.of(Map.of("type", "REPLY", "id", reply)),
                "question", "회신을 반영해 주세요", "expectedIncidentVersion", incidentVersion(user, flow.incident().incidentId()))), flow.incident().incidentId()).andExpect(status().isAccepted());
        String followupJob = json(accepted, "$.data.jobId"); String arq = json(accepted, "$.data.analysisRequestId"); String result = finish(followupJob, "회신을 인용한 추가 분석", "E-REQ-" + flow.request());
        JsonNode after = data(user, flow.incident().incidentId());
        for (String type : List.of("QUESTION", "APPROVAL_REQUESTED", "APPROVAL_DECIDED", "REQUEST_SENT", "REQUEST_ACCEPTED", "REPLY", "FOLLOWUP_REQUESTED")) one(after, type);
        assertThat(one(after, "REPLY").path("links").path("requestId").asText()).isEqualTo(flow.request());
        assertThat(one(after, "REPLY").path("sourceRef").path("id").asText()).isEqualTo(reply);
        var followup = one(after, "FOLLOWUP_REQUESTED");
        assertThat(followup.path("data").path("followup").path("selectedInputs").get(0).path("content").asText()).isEqualTo("시즈닝 기록 확인 완료");
        assertThat(followup.path("data").path("followup").path("selectedInputs").get(0).path("citedInResult").asBoolean()).isTrue();
        assertThat(followup.path("placement").path("blockId").asText()).isEqualTo("followup:" + arq);
        JsonNode answer = entries(after).stream().filter(e -> result.equals(e.path("links").path("resultId").asText())).findFirst().orElseThrow();
        assertThat(answer.path("links").path("analysisRequestId").asText()).isEqualTo(arq);
        assertThat(answer.path("links").path("rootJobId").asText()).isEqualTo(flow.question());
        assertThat(answer.path("links").path("parentJobId").asText()).isEqualTo(flow.resume());
        assertThat(answer.path("placement").path("blockId").asText()).isEqualTo("followup:" + arq);
        assertThat(one(after, "REPLY").path("placement").path("blockId").asText()).isEqualTo("job:" + flow.question() + ":requested");
        assertThat(after.path("jobs").size()).isEqualTo(4);
        assertThat(one(after, "QUESTION").path("currentJob").path("jobId").asText()).isEqualTo(followupJob);
        assertThat(one(before, "QUESTION").path("currentJob").path("status").asText()).isEqualTo("WAITING_REPLY");
        var ordered = entries(after);
        var approvalEntry = one(after, "APPROVAL_REQUESTED");
        var basis = ordered.stream().filter(e -> e.path("links").path("resultId").asText().equals(approvalEntry.path("data").path("approval").path("sourceResultId").asText())).findFirst().orElseThrow();
        assertThat(ordered.indexOf(basis)).isLessThan(ordered.indexOf(approvalEntry));
        assertThat(after.path("investigationResults").get(0).path("analysisUse").path("successfulCount").asInt()).isEqualTo(1);
        assertThat(after.toString()).doesNotContain("INT-private", "INT-REPLY-private", "passwordHash");
        assertThat(entries(before).stream().filter(e -> e.path("type").asText().equals("AI_ANSWER"))).hasSize(2);
    }

    @Test void pendingCardRemainsAvailableOutsideTheCurrentPageAndDoesNotExpireOnRead() throws Exception {
        Session user = cmp(); var incident = base(true);
        var approval = approvals.save(Approval.builder().approvalId("apv_" + UUID.randomUUID()).incidentId(incident.incidentId()).processCode("CMP")
                .actionType(ApprovalActionType.CHECK_REQUEST).payload(Map.of("question", "확인" )).rationale("검토 필요").source("USER").createdBy("USR-CMP-01")
                .createdAt(Instant.parse("2026-01-01T00:00:00Z")).expiresAt(Instant.parse("2026-01-02T00:00:00Z")).build());
        var response = getAs(user, PATH + "?size=1", incident.incidentId()).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.pendingApprovals[0].approvalId").value(approval.getApprovalId()))
                .andExpect(jsonPath("$.data.pendingApprovals[0].status").value("PENDING"));
        assertThat(approvals.findById(approval.getApprovalId()).orElseThrow().getStatus()).isEqualTo(ApprovalStatus.PENDING);
    }

    @Test void agentCardIsPublishedOnlyWhenItsJobReachesTheApprovalCheckpoint() throws Exception {
        Session user = cmp(); var incident = base(true); String question = ask(user, incident.incidentId(), "요청서 작성");
        String approval = json(internal("/internal/jobs/{id}/proposals", Map.of("actionType", "CREATE_CHECK_REQUEST", "payload", Map.of("ownerUserId", "USR-ETCH-01", "targetProcessCode", "ETCH", "question", "기록 확인"),
                "rationale", "확인 필요", "idempotencyKey", "publish-" + question), question).andExpect(status().isCreated()), "$.data.approvalId");
        assertThat(data(user, incident.incidentId()).path("pendingApprovals").size()).isZero();
        event(question, "APPROVAL_REQUIRED", Map.of("approvalId", approval, "approvalVersion", 1, "interruptId", "private")).andExpect(status().isOk());
        assertThat(data(user, incident.incidentId()).path("pendingApprovals").get(0).path("approvalId").asText()).isEqualTo(approval);
    }

    @Test void recipientRelationDoesNotGrantConversationAccess() throws Exception {
        Session user = cmp(); var flow = requestFlow(user); Session receiver = login("etch.lee");
        getAs(receiver, "/api/check-requests/{id}", flow.request()).andExpect(status().isOk());
        getAs(receiver, PATH, flow.incident().incidentId()).andExpect(status().isForbidden());
        getAs(login("mnt.jung"), PATH, flow.incident().incidentId()).andExpect(status().isOk());
        mvc.perform(get(PATH, flow.incident().incidentId())).andExpect(status().isUnauthorized());
    }

    @Test void actionRegistrationTimeAndSourceArePreservedWithoutAutomaticAnalysis() throws Exception {
        Session user = cmp(); var incident = base(true); long count = jobs.count();
        String action = json(postAs(user, "/api/incidents/{id}/actions", Json.write(Map.of("actionType", "INSPECTION", "description", "직접 압력 상태 확인", "performedAt", "2026-01-01T00:00:00Z",
                "confirmedByUser", true, "expectedIncidentVersion", incidentVersion(user, incident.incidentId()))), incident.incidentId()).andExpect(status().isCreated()), "$.data.actionId");
        JsonNode entry = one(data(user, incident.incidentId()), "ACTION_RESULT");
        assertThat(entry.path("links").path("actionId").asText()).isEqualTo(action);
        assertThat(entry.path("data").path("action").path("performedAt").asText()).isEqualTo("2026-01-01T00:00:00Z");
        assertThat(Instant.parse(entry.path("occurredAt").asText())).isAfter(Instant.parse("2026-01-01T00:00:00Z"));
        assertThat(entry.path("sourceRef").path("type").asText()).isEqualTo("ACTION"); assertThat(jobs.count()).isEqualTo(count);
    }

    @Test void newThreadFollowupKeepsSelectedActionSnapshotAndCitation() throws Exception {
        Session user = cmp(); var incident = base(true);
        String action = json(postAs(user, "/api/incidents/{id}/actions", Json.write(Map.of("actionType", "INSPECTION", "description", "작업자 실측 결과", "performedAt", "2026-10-22T00:00:00Z",
                "confirmedByUser", true, "expectedIncidentVersion", incidentVersion(user, incident.incidentId()))), incident.incidentId()).andExpect(status().isCreated()), "$.data.actionId");
        var accepted = postAs(user, "/api/incidents/{id}/followup-analysis-jobs", Json.write(Map.of("sourceRefs", List.of(Map.of("type", "ACTION", "id", action)),
                "question", "작업 결과를 반영해 주세요", "expectedIncidentVersion", incidentVersion(user, incident.incidentId()))), incident.incidentId()).andExpect(status().isAccepted());
        String job = json(accepted, "$.data.jobId");
        assertThat(jobs.findById(job).orElseThrow().getThreadId()).isNotEqualTo(jobs.findById(incident.jobId()).orElseThrow().getThreadId());
        assertThat(one(data(user, incident.incidentId()), "FOLLOWUP_REQUESTED").path("data").path("followup").path("selectedInputs").get(0).get("citedInResult").isNull()).isTrue();
        finish(job, "선택한 작업 결과를 반영한 답", "E-ACT-" + action);
        JsonNode data = data(user, incident.incidentId()); JsonNode entry = one(data, "FOLLOWUP_REQUESTED");
        assertThat(entry.path("data").path("followup").path("selectedInputs").get(0).path("citedInResult").asBoolean()).isTrue();
        assertThat(entry.path("data").path("followup").path("selectedInputs").get(0).path("content").asText()).isEqualTo("작업자 실측 결과");
        assertThat(entry.path("links").path("rootJobId").asText()).isEqualTo(job);
    }

    @Test void rejectedAndWithdrawnRequestsHaveSeparatePersistentRecords() throws Exception {
        Session user = cmp(); var rejected = requestFlow(user); Session receiver = login("etch.lee");
        postAs(receiver, "/api/check-requests/{id}/decisions", "{\"decision\":\"REJECT\",\"comment\":\"담당 설비가 다릅니다\",\"expectedVersion\":1}", rejected.request()).andExpect(status().isOk());
        JsonNode rejection = one(data(user, rejected.incident().incidentId()), "REQUEST_REJECTED");
        assertThat(rejection.path("text").asText()).isEqualTo("담당 설비가 다릅니다");
        assertThat(rejection.path("sourceRef").path("type").asText()).isEqualTo("REQUEST_DECISION");
        assertThat(rejection.path("sourceRef").path("id").asText()).isEqualTo(rejected.request());
        var withdrawn = requestFlow(user);
        postAs(user, "/api/check-requests/{id}/withdrawals", "{\"reason\":\"다른 확인으로 대체함\",\"expectedVersion\":1}", withdrawn.request()).andExpect(status().isOk());
        JsonNode data = data(user, withdrawn.incident().incidentId());
        assertThat(one(data, "REQUEST_WITHDRAWN").path("text").asText()).isEqualTo("다른 확인으로 대체함");
        assertThat(one(data, "REQUEST_SENT").path("links").path("requestId").asText()).isEqualTo(withdrawn.request());
        assertThat(one(data, "REQUEST_WITHDRAWN").path("data").path("request").path("status").asText()).isEqualTo("CANCELLED");
    }

    @Test void failedAnalysisDoesNotInventAnAnswerOrReplaceTheCauseAnalysis() throws Exception {
        Session user = cmp(); var incident = base(true); String job = ask(user, incident.incidentId(), "실패할 질문");
        event(job, "JOB_FAILED", Map.of("errorCode", "MODEL_ERROR", "message", "분석 처리 실패")).andExpect(status().isOk());
        JsonNode data = data(user, incident.incidentId());
        assertThat(one(data, "ANALYSIS_FAILED").path("job").path("error").path("code").asText()).isEqualTo("MODEL_ERROR");
        assertThat(entries(data).stream().filter(e -> "AI_ANSWER".equals(e.path("type").asText()))).hasSize(1);
        assertThat(data.path("latestCauseAnalysis").path("jobId").asText()).isEqualTo(incident.jobId());
    }

    @Test void cyclicLegacyParentsAreReportedWithoutRecursingForever() throws Exception {
        Session user = cmp(); var incident = base(true);
        tx.executeWithoutResult(s -> {
            Job first = rawJob(incident.incidentId(), JobKind.DEEP, "cycle", null, Instant.now(), Map.of());
            Job second = rawJob(incident.incidentId(), JobKind.RESUME, "cycle", first.getJobId(), Instant.now(), Map.of());
            org.springframework.test.util.ReflectionTestUtils.setField(first, "parentJobId", second.getJobId());
        });
        JsonNode data = data(user, incident.incidentId());
        assertThat(entries(data).stream().filter(e -> "CYCLE".equals(e.path("links").path("linkStatus").asText()))).isNotEmpty();
    }

    @Test void workProposalAndHumanDecisionAreKeptAsSeparateRecords() throws Exception {
        Session user = cmp(); var incident = base(true); String result = results.findFirstByJobIdOrderByCreatedAtDesc(incident.jobId()).orElseThrow().getResultId();
        String task = json(postAs(user, "/api/incidents/{id}/work-items", Json.write(Map.of("operationCode", "REVIEW_SENSOR_DATA", "title", "상태 확인", "reason", "추가 확인 필요",
                "sourceResultId", result, "expectedIncidentVersion", incidentVersion(user, incident.incidentId()))), incident.incidentId()).andExpect(status().isCreated()), "$.data.taskId");
        postAs(user, "/api/work-items/{id}/decisions", "{\"decision\":\"DECLINE\",\"expectedVersion\":1}", task).andExpect(status().isOk());
        JsonNode data = data(user, incident.incidentId()); assertThat(one(data, "WORK_PROPOSED").path("links").path("taskId").asText()).isEqualTo(task);
        assertThat(one(data, "WORK_EVENT").path("eventType").asText()).isEqualTo("WORK_DECIDED");
        assertThat(one(data, "WORK_EVENT").path("data").path("work").path("status").asText()).isEqualTo("DECLINED");
    }

    @Test void keysetPagingIsStableForTiesAndNewlyInsertedRecords() throws Exception {
        Session user = cmp(); var incident = base(true); Instant time = Instant.parse("2026-04-01T00:00:00Z");
        tx.executeWithoutResult(s -> { for (int i = 0; i < 8; i++) rawJob(incident.incidentId(), JobKind.DEEP, "thread-tie-" + i, null, time, Map.of()); });
        JsonNode first = new tools.jackson.databind.json.JsonMapper().readTree(getAs(user, PATH + "?size=3", incident.incidentId()).andExpect(status().isOk()).andReturn().getResponse().getContentAsString()).get("data");
        String cursor = first.path("nextCursor").asText(); assertThat(cursor).isNotBlank();
        tx.executeWithoutResult(s -> rawJob(incident.incidentId(), JobKind.DEEP, "thread-new", null, Instant.now().plusSeconds(10), Map.of()));
        List<String> ids = new ArrayList<>(entries(first).stream().map(e -> e.path("entryId").asText()).toList());
        while (cursor != null && !cursor.isEmpty()) {
            JsonNode page = new tools.jackson.databind.json.JsonMapper().readTree(mvc.perform(get(PATH, incident.incidentId()).session(user.http()).param("size", "3").param("cursor", cursor))
                    .andExpect(status().isOk()).andReturn().getResponse().getContentAsString()).get("data");
            ids.addAll(entries(page).stream().map(e -> e.path("entryId").asText()).toList()); cursor = page.get("nextCursor").isNull() ? null : page.path("nextCursor").asText();
        }
        assertThat(ids).hasSize(10).doesNotHaveDuplicates();
        assertThat(entries(data(user, incident.incidentId()))).hasSize(11);
    }

    @Test void cursorIsIncidentScopedAndBadInputsAreRejected() throws Exception {
        Session user = cmp(); var incident = base(true); var other = base(true);
        String cursor = json(getAs(user, PATH + "?size=1", incident.incidentId()).andExpect(status().isOk()), "$.data.nextCursor");
        mvc.perform(get(PATH, other.incidentId()).session(user.http()).param("cursor", cursor)).andExpect(status().isBadRequest()).andExpect(jsonPath("$.error.code").value("INVALID_CURSOR"));
        for (String invalid : List.of("", "garbage", "a".repeat(2100))) mvc.perform(get(PATH, incident.incidentId()).session(user.http()).param("cursor", invalid)).andExpect(status().isBadRequest());
        getAs(user, PATH + "?size=0", incident.incidentId()).andExpect(status().isBadRequest());
        getAs(user, PATH + "?size=101", incident.incidentId()).andExpect(status().isBadRequest());
        getAs(user, PATH, "inc_missing").andExpect(status().isNotFound());
    }

    @Test void aDanglingParentDoesNotFetchAnotherIncidentsJob() throws Exception {
        Session user = cmp(); var incident = base(true); var other = base(true);
        Job broken = tx.execute(s -> rawJob(incident.incidentId(), JobKind.DEEP, "broken", other.jobId(), Instant.now(), Map.of("interruptId", "private-secret")));
        JsonNode data = data(user, incident.incidentId());
        JsonNode entry = entries(data).stream().filter(e -> broken.getJobId().equals(e.path("links").path("jobId").asText())).findFirst().orElseThrow();
        assertThat(entry.path("links").path("linkStatus").asText()).isEqualTo("MISSING_PARENT");
        assertThat(entry.get("placement").isNull()).isTrue();
        assertThat(data.toString()).doesNotContain(other.incidentId(), "private-secret");
        assertThat(data.path("jobs").size()).isEqualTo(2);
    }

    @Test void reportsAndSummariesAreNotConversationJobs() throws Exception {
        Session user = cmp(); var incident = base(true);
        tx.executeWithoutResult(s -> { rawJob(incident.incidentId(), JobKind.REPORT, "report-thread", null, Instant.now(), Map.of());
            rawJob(incident.incidentId(), JobKind.SUMMARY, "summary-thread", null, Instant.now(), Map.of()); });
        JsonNode data = data(user, incident.incidentId()); assertThat(data.path("jobs").size()).isEqualTo(1);
        assertThat(data.toString()).doesNotContain("report-thread", "summary-thread");
    }

    @Test void queryCountDoesNotGrowWithQuestionCountAndSwaggerIsConcrete() throws Exception {
        Session user = cmp(); var incident = base(true);
        var stats = emf.unwrap(SessionFactory.class).getStatistics(); stats.clear(); data(user, incident.incidentId()); long initial = stats.getPrepareStatementCount();
        tx.executeWithoutResult(s -> {
            for (int i = 0; i < 25; i++) {
                Job job = rawJob(incident.incidentId(), JobKind.DEEP, "many-" + i, null, Instant.now(), Map.of());
                results.save(AnalysisResult.builder().resultId("res_sql_" + UUID.randomUUID()).incidentId(incident.incidentId()).jobId(job.getJobId())
                        .threadId(job.getThreadId()).kind("DEEP").summary("과거 답변").answer("과거 질문 답변").createdAt(Instant.now()).build());
            }
            for (int i = 0; i < 5; i++) actions.save(IncidentAction.builder().actionId("act_sql_" + UUID.randomUUID()).incidentId(incident.incidentId()).processCode("CMP")
                    .actionType("INSPECTION").description("조회 수 검증").performedBy("USR-CMP-01").performedAt(Instant.now()).createdAt(Instant.now()).source("USER").build());
        });
        stats.clear(); JsonNode data = data(user, incident.incidentId()); long many = stats.getPrepareStatementCount();
        System.out.println("conversationQueries initial=" + initial + " after25Questions=" + many);
        assertThat(data.path("jobs").size()).isEqualTo(26); assertThat(many).isEqualTo(initial).isLessThanOrEqualTo(14);
        String document = mvc.perform(get("/v3/api-docs")).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        JsonNode schema = new tools.jackson.databind.json.JsonMapper().readTree(document);
        assertThat(schema.at("/paths/~1api~1incidents~1{incidentId}~1conversation/get/responses/200/content/application~1json/schema/$ref").asText()).endsWith("/IncidentConversationEnvelope");
        assertThat(schema.at("/components/schemas/ConversationEntry/properties/data/$ref").asText()).isNotEmpty();
    }
}
