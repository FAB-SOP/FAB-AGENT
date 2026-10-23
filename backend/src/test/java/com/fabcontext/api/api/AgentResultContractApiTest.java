package com.fabcontext.api.api;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import java.util.*;
import com.fabcontext.api.common.Json;
import com.fabcontext.api.domain.job.*;
import com.fabcontext.api.domain.analysis.AnalysisResultRepository;
import com.fabcontext.api.support.ApiTestSupport;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.ResultActions;

@TestPropertySource(properties={"fab.agent.mode=external", "fab.priority.worker-enabled=false", "fab.agent.reply-flow=WAIT",
        "spring.jpa.properties.hibernate.default_schema=backend_result_contract", "spring.datasource.hikari.schema=backend_result_contract"})
class AgentResultContractApiTest extends ApiTestSupport {
    static final String TOKEN="Bearer dev-service-token";
    @Autowired JobRepository jobs;
    @Autowired AnalysisResultRepository results;
    @Autowired com.fabcontext.api.domain.incident.IncidentRepository incidents;
    @Autowired org.springframework.transaction.support.TransactionTemplate tx;
    private ResultActions internal(String path, Map<String,Object> data, Object...args) throws Exception {
        return mvc.perform(post(path,args).header("Authorization",TOKEN).contentType(MediaType.APPLICATION_JSON).content(Json.write(data)));
    }
    private ResultActions event(Job job,String type,Map<String,Object> payload) throws Exception {
        return event(job,type,payload,"EVT-"+job.getJobId()+"-"+type);
    }
    private ResultActions event(Job job,String type,Map<String,Object> payload,String eventId) throws Exception {
        return internal("/internal/jobs/{id}/events", Map.of("schemaVersion","1.0","eventId",eventId,"jobId",job.getJobId(),
                "incidentId",job.getIncidentId(),"attempt",1,"type",type,"payload",payload),job.getJobId());
    }
    private Job create() throws Exception {
        var accepted=ingest(Json.write(Map.of("eventId","EVT-CONTRACT-"+UUID.randomUUID(),"anomalyType","THICKNESS_OOC_R1","severity","LOW",
                "processCode","CMP","equipmentId","CMP-PM3","detectedAt","2026-10-19T01:30:00Z"))).andExpect(status().isAccepted());
        return jobs.findById(json(accepted,"$.data.jobId")).orElseThrow();
    }
    private Map<String,Object> payload(Job job,String kind) {
        return Map.of("result",Map.of("resultId","RESULT-"+job.getJobId(),"jobId",job.getJobId(),"incidentId",job.getIncidentId(),
                "answer","측정 결과를 확인했으며 추가 점검이 필요합니다.","dataVersion","test-v2",
                "observedFacts",List.of(Map.of("text","작업자 확인 결과","evidenceIds",List.of("E-ACT-act_1"))),
                "candidateCauses",List.of(Map.of("candidateId","CMP_PRESSURE","causeIds",List.of("CMP_PRESSURE"),"priority",1,
                        "hypothesis","압력 제어 확인","stance","REFUTED","supportingEvidenceIds",List.of("E-ACT-act_1"),
                        "conflictingEvidenceIds",List.of("E-RUN-1"),"missingData",List.of(),"nextCheck","설정값 확인")),
                "limitations",List.of("확정 아님"),"evidenceRefs",List.of(Map.of("evidenceId","E-ACT-act_1","sourceType","ACTION","sourceId","act_1"))),
                "analysis",Map.of("resultKind",kind,"nextStep","CURRENT_CHECK","conclusion",Map.of("changed",false,"fields",List.of(),"reason","","newEvidenceIds",List.of(),"adjusted",false)),
                "workSuggestions",List.of());
    }
    private Job question(Session user,Job previous) throws Exception {
        String id=json(postAs(user,"/api/incidents/{id}/questions",Json.write(Map.of("question","다른 가능성은?","expectedIncidentVersion",incidentVersion(user,previous.getIncidentId()))),previous.getIncidentId())
                .andExpect(status().isAccepted()),"$.data.jobId");
        return jobs.findById(id).orElseThrow();
    }
    private String propose(Job job) throws Exception {
        return json(internal("/internal/jobs/{id}/proposals", Map.of("actionType","CREATE_CHECK_REQUEST",
                "payload",Map.of("ownerUserId","USR-ETCH-01","targetProcessCode","ETCH","question","정비 기록 확인"),
                "rationale","원인 확인", "idempotencyKey","proposal-"+job.getJobId()),job.getJobId()).andExpect(status().isCreated()),"$.data.approvalId");
    }
    @Test void newInputIsPublicButPrivateFieldsStayHidden() throws Exception {
        Job job=create();
        mvc.perform(get("/internal/jobs/{id}/context",job.getJobId()).header("Authorization",TOKEN)).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.job.input.purpose").value("BASIC"))
                .andExpect(jsonPath("$.data.job.input.replyFlow").value("WAIT"))
                .andExpect(jsonPath("$.data.job.input.interruptId").doesNotExist());
    }
    @Test void resultPreservesAnswerMetadataCandidateIdentityAndActionEvidence() throws Exception {
        Session user=cmp(); Job job=create();
        event(job,"RESULT_READY",payload(job,"CAUSE_ANALYSIS")).andExpect(status().isOk());
        getAs(user,"/api/incidents/{id}/analysis-results",job.getIncidentId()).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.answer").value("측정 결과를 확인했으며 추가 점검이 필요합니다."))
                .andExpect(jsonPath("$.data.analysis.nextStep").value("CURRENT_CHECK"))
                .andExpect(jsonPath("$.data.resultKind").value("CAUSE_ANALYSIS"))
                .andExpect(jsonPath("$.data.candidateCauses[0].candidateId").value("CMP_PRESSURE"))
                .andExpect(jsonPath("$.data.candidateCauses[0].stance").value("REFUTED"))
                .andExpect(jsonPath("$.data.candidateCauses[0].status").value("NEEDS_CHECK"))
                .andExpect(jsonPath("$.data.evidenceRefs[0].type").value("ACTION"));
        event(job,"RESULT_READY",payload(job,"CAUSE_ANALYSIS")).andExpect(jsonPath("$.data.applied").value(false));
        assertThat(results.findByIncidentIdOrderByCreatedAtDesc(job.getIncidentId())).hasSize(1);
    }
    @Test void narrowQuestionDoesNotReplaceTopCauseAnalysisButHistoryStillHasBoth() throws Exception {
        Session user=cmp();Job basic=create();event(basic,"RESULT_READY",payload(basic,"CAUSE_ANALYSIS"));
        Job question=question(user,basic);event(question,"RESULT_READY",payload(question,"ANSWER")).andExpect(status().isOk());
        getAs(user,"/api/incidents/{id}/analysis-results",basic.getIncidentId()).andExpect(jsonPath("$.data.jobId").value(basic.getJobId()));
        getAs(user,"/api/incidents/{id}/analysis-results?latest=false",basic.getIncidentId())
                .andExpect(jsonPath("$.data.jobId").value(question.getJobId())).andExpect(jsonPath("$.data.history.length()").value(2));
        String id=results.findFirstByJobIdOrderByCreatedAtDesc(question.getJobId()).orElseThrow().getResultId();
        getAs(user,"/api/incidents/{id}/analysis-results?resultId="+id,basic.getIncidentId()).andExpect(jsonPath("$.data.jobId").value(question.getJobId()));
    }
    @Test void approvalWaitPublishesAnswerWithoutCompletingJobAndLinksResumeRevision() throws Exception {
        Session user=cmp();Job basic=create();event(basic,"RESULT_READY",payload(basic,"CAUSE_ANALYSIS"));
        Job question=question(user,basic);String approval=propose(question);
        var wait=new LinkedHashMap<>(payload(question,"ANSWER"));wait.putAll(Map.of("approvalId",approval,"approvalVersion",1,"interruptId","int_test"));
        event(question,"APPROVAL_REQUIRED",wait).andExpect(status().isOk());
        assertThat(jobs.findById(question.getJobId()).orElseThrow().getStatus()).isEqualTo(JobStatus.WAITING_APPROVAL);
        String source=results.findFirstByJobIdOrderByCreatedAtDesc(question.getJobId()).orElseThrow().getResultId();
        getAs(user,"/api/approvals/{id}",approval).andExpect(jsonPath("$.data.sourceResultId").value(source));
        String resumeId=json(postAs(user,"/api/approvals/{id}/decisions","{\"decision\":\"REJECT\",\"comment\":\"이미 확인함\",\"expectedVersion\":1}",approval)
                .andExpect(status().isOk()),"$.data.resumeJobId");
        Job resume=jobs.findById(resumeId).orElseThrow();event(resume,"RESULT_READY",payload(resume,"CAUSE_ANALYSIS")).andExpect(status().isOk());
        var result=results.findFirstByJobIdOrderByCreatedAtDesc(resumeId).orElseThrow();
        assertThat(result.getPreviousResultId()).isEqualTo(source);assertThat(result.getRevision()).isEqualTo(2);
        assertThat(results.findById(source)).isPresent();
    }
    @Test void invalidStanceReturnsFieldErrorAndDoesNotPersistReceiptOrResult() throws Exception {
        Job job=create();var incoming=new LinkedHashMap<>(payload(job,"CAUSE_ANALYSIS"));
        var result=new LinkedHashMap<>((Map<String,Object>)incoming.get("result"));
        result.put("candidateCauses",List.of(Map.of("stance","CONFIRMED")));incoming.put("result",result);
        event(job,"RESULT_READY",incoming).andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error.details.errors[0].field").value("payload.result.candidateCauses.stance"));
        assertThat(results.findByIncidentIdOrderByCreatedAtDesc(job.getIncidentId())).isEmpty();
        event(job,"RESULT_READY",payload(job,"CAUSE_ANALYSIS")).andExpect(jsonPath("$.data.applied").value(true));
    }
    @Test void wrongIncidentAndApprovalCannotBeAttachedToAnswer() throws Exception {
        Job first=create(), other=create();String approval=propose(other);
        var incoming=new LinkedHashMap<>(payload(first,"CAUSE_ANALYSIS"));incoming.putAll(Map.of("approvalId",approval,"approvalVersion",1,"interruptId","int_other"));
        event(first,"APPROVAL_REQUIRED",incoming).andExpect(status().isUnprocessableEntity());
        assertThat(results.findByIncidentIdOrderByCreatedAtDesc(first.getIncidentId())).isEmpty();
    }
    @Test void selectedResultIsStructuredAndFrozenBeforeLaterWorkResultArrives() throws Exception {
        Session user=cmp();Job basic=create();event(basic,"RESULT_READY",payload(basic,"CAUSE_ANALYSIS"));
        String id=basic.getIncidentId();
        String action=json(postAs(user,"/api/incidents/{id}/actions",Json.write(Map.of("actionType","INSPECTION","description","압력 실측값 정상",
                "performedAt","2026-10-23T01:00:00Z","confirmedByUser",true,"expectedIncidentVersion",incidentVersion(user,id))),id)
                .andExpect(status().isCreated()),"$.data.actionId");
        String jobId=json(postAs(user,"/api/incidents/{id}/followup-analysis-jobs",Json.write(Map.of("sourceRefs",List.of(Map.of("type","ACTION","id",action)),
                "question","확인 결과 반영", "expectedIncidentVersion",incidentVersion(user,id))),id).andExpect(status().isAccepted()),"$.data.jobId");
        postAs(user,"/api/incidents/{id}/actions",Json.write(Map.of("actionType","INSPECTION","description","분석 접수 뒤의 새 결과",
                "performedAt","2026-10-23T01:10:00Z","confirmedByUser",true,"expectedIncidentVersion",incidentVersion(user,id))),id).andExpect(status().isCreated());
        mvc.perform(get("/internal/jobs/{id}/context",jobId).header("Authorization",TOKEN)).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.job.input.purpose").value("FOLLOWUP"))
                .andExpect(jsonPath("$.data.job.input.followup.selectedInputs.length()").value(1))
                .andExpect(jsonPath("$.data.job.input.followup.selectedInputs[0].evidenceId").value("E-ACT-"+action))
                .andExpect(jsonPath("$.data.job.input.followup.selectedInputs[0].content").value("압력 실측값 정상"))
                .andExpect(jsonPath("$.data.job.input.followup.selectedInputs[0].incidentId").doesNotExist());
    }
    @Test void requestDispatchResultKeepsPreviousAnalysisAndIsNotAnotherAnalysisCompletion() throws Exception {
        Session user=cmp();Job basic=create();event(basic,"RESULT_READY",payload(basic,"CAUSE_ANALYSIS"));
        Job question=question(user,basic);String approval=propose(question);
        var wait=new LinkedHashMap<>(payload(question,"ANSWER"));wait.putAll(Map.of("approvalId",approval,"approvalVersion",1,"interruptId","int_send"));
        event(question,"APPROVAL_REQUIRED",wait).andExpect(status().isOk());
        String resume=json(postAs(user,"/api/approvals/{id}/decisions","{\"decision\":\"APPROVE\",\"expectedVersion\":1}",approval)
                .andExpect(status().isOk()),"$.data.resumeJobId");
        String request=json(internal("/internal/approved-actions/check-request",Map.of("approvalId",approval,"idempotencyKey","exec-"+approval))
                .andExpect(status().isOk()),"$.data.data.requestId");
        mvc.perform(get("/internal/incidents/{id}/check-requests",basic.getIncidentId()).header("Authorization",TOKEN))
                .andExpect(jsonPath("$.data.data.items[0].origin").value("AGENT_PROPOSAL"));
        Job job=jobs.findById(resume).orElseThrow();
        event(job,"RESULT_READY",Map.of("analysis",Map.of("requestSent",request))).andExpect(status().isOk());
        assertThat(results.findByIncidentIdOrderByCreatedAtDesc(basic.getIncidentId())).hasSize(2);
        assertThat(jobs.findById(resume).orElseThrow().getResultRefType()).isEqualTo("CHECK_REQUEST");
        getAs(user,"/api/incidents/{id}/analysis-results",basic.getIncidentId()).andExpect(jsonPath("$.data.jobId").value(basic.getJobId()));
    }
    @Test void historyIsBoundedValidJsonWithLatestCauseAndPendingApprovalPreserved() throws Exception {
        Session user=cmp();Job basic=create();event(basic,"RESULT_READY",payload(basic,"CAUSE_ANALYSIS"));
        Job question=question(user,basic);String approval=propose(question);
        var wait=new LinkedHashMap<>(payload(question,"ANSWER"));wait.putAll(Map.of("approvalId",approval,"approvalVersion",1,"interruptId","int_history"));
        event(question,"APPROVAL_REQUIRED",wait).andExpect(status().isOk());
        tx.executeWithoutResult(status->{var incident=incidents.findById(basic.getIncidentId()).orElseThrow();
            for(int n=0;n<80;n++) incident.record(java.time.Instant.now(),"HUMAN_NOTE","USR-CMP-01","긴 기록 "+n+" 가".repeat(300),"JOB",basic.getJobId());});
        var response=mvc.perform(get("/internal/incidents/{id}/history",basic.getIncidentId()).header("Authorization",TOKEN))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.latestCauseAnalysis.jobId").value(basic.getJobId()))
                .andExpect(jsonPath("$.data.pendingApproval.approvalId").value(approval))
                .andExpect(jsonPath("$.data.pendingApproval.actionType").value("CREATE_CHECK_REQUEST"))
                .andExpect(jsonPath("$.data.omitted.items").value(org.hamcrest.Matchers.greaterThan(0)))
                .andExpect(jsonPath("$.data.detailPaths").doesNotExist());
        assertThat(response.andReturn().getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8).length()).isLessThanOrEqualTo(5000);
        mvc.perform(get("/internal/incidents/{id}/history",basic.getIncidentId())).andExpect(status().isUnauthorized());
        mvc.perform(get("/internal/incidents/{id}/history",basic.getIncidentId()).session(user.http())).andExpect(status().isForbidden());
    }
    @Test void longAnswerIsPreservedWhileTimelineRespectsItsColumnLimit() throws Exception {
        Session user=cmp();Job job=create();
        String answer="상세 조사 결과를 확인했습니다. ".repeat(250);
        var incoming=new LinkedHashMap<>(payload(job,"CAUSE_ANALYSIS"));
        var result=new LinkedHashMap<>((Map<String,Object>)incoming.get("result"));result.put("answer",answer);incoming.put("result",result);
        event(job,"RESULT_READY",incoming).andExpect(status().isOk());
        getAs(user,"/api/incidents/{id}/analysis-results",job.getIncidentId()).andExpect(jsonPath("$.data.answer").value(answer));
        tx.executeWithoutResult(status->{var timeline=incidents.findById(job.getIncidentId()).orElseThrow().getTimeline();
            assertThat(timeline).allSatisfy(t->assertThat(t.getMessage().length()).isLessThanOrEqualTo(1000));});
    }
    @Test void staleApprovalVersionCannotPublishAnAnswer() throws Exception {
        Job basic=create();String approval=propose(basic);
        var incoming=new LinkedHashMap<>(payload(basic,"CAUSE_ANALYSIS"));incoming.putAll(Map.of("approvalId",approval,"approvalVersion",2,"interruptId","int_stale"));
        event(basic,"APPROVAL_REQUIRED",incoming).andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error.details.errors[0].field").value("payload.approvalVersion"));
        assertThat(results.findByIncidentIdOrderByCreatedAtDesc(basic.getIncidentId())).isEmpty();
        assertThat(jobs.findById(basic.getJobId()).orElseThrow().getStatus()).isEqualTo(JobStatus.QUEUED);
    }
    @Test void legacyResultsRemainAcceptedWithoutInventedStanceOrAnswer() throws Exception {
        Session user=cmp();Job job=create();
        event(job,"RESULT_READY",Map.of("result",Map.of("observedFacts",List.of(),"candidateCauses",List.of(),"evidenceRefs",List.of(),"limitations",List.of())))
                .andExpect(status().isOk());
        getAs(user,"/api/incidents/{id}/analysis-results",job.getIncidentId()).andExpect(jsonPath("$.data.resultKind").value("CAUSE_ANALYSIS"))
                .andExpect(jsonPath("$.data.answer").doesNotExist());
    }
    private ResultActions reportContext(String incident) throws Exception {
        return mvc.perform(get("/internal/incidents/{id}/report-context", incident).header("Authorization", TOKEN));
    }

    @Test void reportContextAlwaysContainsExplicitNullBeforeAnyCauseAnalysis() throws Exception {
        Job job = create();
        var before = incidents.findById(job.getIncidentId()).orElseThrow();
        long version = before.getVersion();
        int timeline = tx.execute(t -> incidents.findById(job.getIncidentId()).orElseThrow().getTimeline().size());
        var response = reportContext(job.getIncidentId()).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.data.incident.incidentId").value(job.getIncidentId()))
                .andExpect(jsonPath("$.data.data.checkRequests").isArray())
                .andExpect(jsonPath("$.data.data.actions").isArray())
                .andExpect(jsonPath("$.data.data.reports").isArray())
                .andExpect(jsonPath("$.data.data.workItems").isArray())
                .andExpect(jsonPath("$.data.evidenceRefs").isArray());
        assertThat(Json.tree(json(response, "$.data.data")).get("latestCauseAnalysis").isNull()).isTrue();
        assertThat(results.findByIncidentIdOrderByCreatedAtDesc(job.getIncidentId())).isEmpty();
        assertThat(jobs.findById(job.getJobId()).orElseThrow().getStatus()).isEqualTo(JobStatus.QUEUED);
        var after = incidents.findById(job.getIncidentId()).orElseThrow();
        assertThat(after.getVersion()).isEqualTo(version);
        int afterTimeline = tx.execute(t -> incidents.findById(job.getIncidentId()).orElseThrow().getTimeline().size());
        assertThat(afterTimeline).isEqualTo(timeline);
    }

    @Test void reportContextDoesNotTreatAnAnswerOnlyHistoryAsCauseAnalysis() throws Exception {
        Session user = cmp(); Job basic = create();
        event(basic, "JOB_FAILED", Map.of("errorCode", "TEST", "message", "no initial result"));
        Job answer = question(user, basic);
        event(answer, "RESULT_READY", payload(answer, "ANSWER")).andExpect(status().isOk());
        var response = reportContext(basic.getIncidentId()).andExpect(status().isOk());
        assertThat(Json.tree(json(response, "$.data.data")).get("latestCauseAnalysis").isNull()).isTrue();
    }

    @Test void reportContextKeepsLatestCauseWhenANarrowAnswerIsNewerAndReusesPublicResultShape() throws Exception {
        Session user = cmp(); Job basic = create();
        event(basic, "RESULT_READY", payload(basic, "CAUSE_ANALYSIS")).andExpect(status().isOk());
        var cause = results.findFirstByJobIdOrderByCreatedAtDesc(basic.getJobId()).orElseThrow();
        Job answer = question(user, basic);
        event(answer, "RESULT_READY", payload(answer, "ANSWER")).andExpect(status().isOk());
        var response = reportContext(basic.getIncidentId()).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.data.latestCauseAnalysis.resultId").value(cause.getResultId()))
                .andExpect(jsonPath("$.data.data.latestCauseAnalysis.jobId").value(basic.getJobId()))
                .andExpect(jsonPath("$.data.data.latestCauseAnalysis.resultKind").value("CAUSE_ANALYSIS"))
                .andExpect(jsonPath("$.data.data.latestCauseAnalysis.analysis.nextStep").value("CURRENT_CHECK"))
                .andExpect(jsonPath("$.data.data.latestCauseAnalysis.candidateCauses[0].stance").value("REFUTED"));
        var published = getAs(user, "/api/incidents/{id}/analysis-results?resultId={result}", basic.getIncidentId(), cause.getResultId())
                .andExpect(status().isOk());
        assertThat(Json.canonical(json(response, "$.data.data.latestCauseAnalysis")))
                .isEqualTo(Json.canonical(json(published, "$.data")));
    }

    @Test void reportContextIncludesDeepCauseAnalysisAndPreservesMissingLegacyMetadata() throws Exception {
        Session user = cmp(); Job basic = create();
        event(basic, "RESULT_READY", payload(basic, "CAUSE_ANALYSIS")).andExpect(status().isOk());
        Job deep = question(user, basic);
        event(deep, "RESULT_READY", payload(deep, "CAUSE_ANALYSIS")).andExpect(status().isOk());
        reportContext(basic.getIncidentId()).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.data.latestCauseAnalysis.jobId").value(deep.getJobId()))
                .andExpect(jsonPath("$.data.data.latestCauseAnalysis.kind").value("DEEP"))
                .andExpect(jsonPath("$.data.data.latestCauseAnalysis.evidenceRefs[0].evidenceId").value("E-ACT-act_1"));
        Job legacy = create();
        event(legacy, "RESULT_READY", Map.of("result", Map.of("observedFacts", List.of(), "candidateCauses", List.of(),
                "evidenceRefs", List.of(), "limitations", List.of()))).andExpect(status().isOk());
        var response = reportContext(legacy.getIncidentId()).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.data.latestCauseAnalysis.observedFacts").isEmpty())
                .andExpect(jsonPath("$.data.data.latestCauseAnalysis.candidateCauses").isEmpty())
                .andExpect(jsonPath("$.data.data.latestCauseAnalysis.analysis.nextStep").doesNotExist());
        assertThat(Json.tree(json(response, "$.data.data.latestCauseAnalysis")).get("answer").isNull()).isTrue();
    }

    @Test void reportContextStillRequiresServiceAuthenticationAndRejectsMissingIncident() throws Exception {
        mvc.perform(get("/internal/incidents/EV-1009/report-context")).andExpect(status().isUnauthorized());
        mvc.perform(get("/internal/incidents/EV-1009/report-context").session(cmp().http())).andExpect(status().isForbidden());
        reportContext("not-an-incident").andExpect(status().isNotFound());
    }

}
