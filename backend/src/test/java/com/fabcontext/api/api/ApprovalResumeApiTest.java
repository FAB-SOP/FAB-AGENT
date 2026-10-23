package com.fabcontext.api.api;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.hamcrest.Matchers.*;

import java.util.*;
import java.util.concurrent.*;
import com.fabcontext.api.common.Json;
import com.fabcontext.api.domain.job.*;
import com.fabcontext.api.domain.incident.IncidentRepository;
import com.fabcontext.api.support.ApiTestSupport;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.support.TransactionTemplate;

@TestPropertySource(properties={"fab.agent.mode=external","fab.priority.worker-enabled=false",
        "spring.jpa.properties.hibernate.default_schema=backend_approval_resume","spring.datasource.hikari.schema=backend_approval_resume"})
class ApprovalResumeApiTest extends ApiTestSupport {
    static final String TOKEN="Bearer dev-service-token";
    @Autowired JobRepository jobs;
    @Autowired JobService jobService;
    @Autowired IncidentRepository incidents;
    @Autowired TransactionTemplate tx;

    private ResultActions internal(String path, Map<String,Object> body,Object...args) throws Exception {
        return mvc.perform(post(path,args).header("Authorization",TOKEN).contentType(MediaType.APPLICATION_JSON).content(Json.write(body)));
    }
    private ResultActions iget(String path,Object...args) throws Exception {return mvc.perform(get(path,args).header("Authorization",TOKEN));}
    private Job load(String id) {return jobs.findById(id).orElseThrow();}
    private ResultActions event(Job job,String type,Map<String,Object> payload) throws Exception {
        return internal("/internal/jobs/{id}/events",Map.of("eventId","EVT-"+job.getJobId()+"-"+type,"jobId",job.getJobId(),"incidentId",job.getIncidentId(),
                "attempt",1,"type",type,"payload",payload),job.getJobId());
    }
    private void finish(Job job) throws Exception {
        event(job,"RESULT_READY",Map.of("result",Map.of("observedFacts",List.of(),"candidateCauses",List.of(),"evidenceRefs",List.of(),"limitations",List.of())))
                .andExpect(status().isOk());
    }
    private Job create() throws Exception {
        var result=ingest(Json.write(Map.of("eventId","EVT-RESUME-"+UUID.randomUUID(),"anomalyType","THICKNESS_OOC_R1","severity","LOW","processCode","CMP",
                "equipmentId","CMP-PM3","detectedAt","2026-10-19T01:30:00Z"))).andExpect(status().isAccepted());
        Job job=load(json(result,"$.data.jobId")); finish(job); return job;
    }
    private Job ask(Session user,String incidentId) throws Exception {
        return load(json(postAs(user,"/api/incidents/{id}/questions",Json.write(Map.of("question","기존 요청은 유지하고 현재 공정 근거만 알려주세요.","scope","CURRENT",
                "expectedIncidentVersion",incidentVersion(user,incidentId))),incidentId).andExpect(status().isAccepted()),"$.data.jobId"));
    }
    private String proposal(Job job,String key) throws Exception {
        return json(internal("/internal/jobs/{id}/proposals",Map.of("actionType","CREATE_CHECK_REQUEST","payload",Map.of("ownerUserId","USR-ETCH-01","targetProcessCode","ETCH","question","ETCH 이력 확인"),
                "idempotencyKey",key,"rationale","근거 확인"),job.getJobId()).andExpect(status().isCreated()),"$.data.approvalId");
    }
    private record Waiting(Job job,String approvalId) {}
    private Waiting waiting(Session user) throws Exception {
        Job base=create();Job question=ask(user,base.getIncidentId());String approval=proposal(question,"proposal-"+question.getJobId());
        event(question,"APPROVAL_REQUIRED",Map.of("approvalId",approval,"approvalVersion",1,"interruptId","INT-"+question.getJobId())).andExpect(status().isOk());
        return new Waiting(load(question.getJobId()),approval);
    }
    private ResultActions decide(Session user,String approval,String decision,String comment) throws Exception {
        Map<String,Object> body=new LinkedHashMap<>(Map.of("decision",decision,"expectedVersion",1));
        if(comment!=null)body.put("comment",comment);
        return postAs(user,"/api/approvals/{id}/decisions",Json.write(body),approval);
    }
    private ResultActions claim(Job job) throws Exception {
        return internal("/internal/jobs/{id}/claims",Map.of("attempt",1,"workerId","worker-"+UUID.randomUUID()),job.getJobId());
    }

    @Test void waitingApprovalRemainsVisibleWhileQuestionRunsAndBlocksAnotherQuestion() throws Exception {
        Session user=cmp();Waiting w=waiting(user);Job question=ask(user,w.job().getIncidentId());claim(question).andExpect(status().isOk());
        getAs(user,"/api/incidents/{id}",question.getIncidentId()).andExpect(jsonPath("$.data.activeJob.jobId").value(question.getJobId()))
                .andExpect(jsonPath("$.data.activeJob.status").value("RUNNING"))
                .andExpect(jsonPath("$.data.waitingJobs[0].jobId").value(w.job().getJobId()))
                .andExpect(jsonPath("$.data.waitingJobs[0].waitingRef.id").value(w.approvalId()));
        postAs(user,"/api/incidents/{id}/questions",Json.write(Map.of("question","동시 질문","expectedIncidentVersion",incidentVersion(user,question.getIncidentId()))),question.getIncidentId())
                .andExpect(status().isConflict()).andExpect(jsonPath("$.error.code").value("JOB_IN_PROGRESS"));
        getAs(user,"/api/approvals/{id}",w.approvalId()).andExpect(jsonPath("$.data.status").value("PENDING"));
    }

    @Test void approvalDuringQueuedQuestionPersistsButCannotStartOrSendUntilQuestionFinishes() throws Exception {
        Session user=cmp();Waiting w=waiting(user);Job question=ask(user,w.job().getIncidentId());
        String resume=json(decide(user,w.approvalId(),"APPROVE",null).andExpect(status().isOk()),"$.data.resumeJobId");
        Job queued=load(resume);
        assertThat(queued.getStatus()).isEqualTo(JobStatus.QUEUED);
        assertThat(queued.getThreadId()).isEqualTo(w.job().getThreadId());
        assertThat(queued.getParentJobId()).isEqualTo(w.job().getJobId());
        assertThat(queued.getRequestedBy()).isEqualTo(w.job().getRequestedBy());
        getAs(user,"/api/approvals/{id}",w.approvalId()).andExpect(jsonPath("$.data.status").value("APPROVED"));
        getAs(user,"/api/incidents/{id}",question.getIncidentId()).andExpect(jsonPath("$.data.activeJob.jobId").value(question.getJobId()))
                .andExpect(jsonPath("$.data.waitingJobs.length()").value(0));
        claim(queued).andExpect(status().isConflict());
        event(queued,"JOB_STARTED",Map.of()).andExpect(status().isConflict());
        internal("/internal/approved-actions/check-request",Map.of("approvalId",w.approvalId(),"idempotencyKey","early-exec-"+resume))
                .andExpect(status().isConflict());
        iget("/internal/jobs?status=QUEUED").andExpect(jsonPath("$.data[*].job.jobId",not(hasItem(resume))));
        claim(question).andExpect(status().isOk());finish(question);
        getAs(user,"/api/incidents/{id}",question.getIncidentId()).andExpect(jsonPath("$.data.activeJob.jobId").value(resume));
        claim(queued).andExpect(status().isOk());
        assertThat(load(resume).getStatus()).isEqualTo(JobStatus.RUNNING);
        assertThat(incidents.findById(question.getIncidentId()).orElseThrow().isActive()).isTrue();
    }

    @Test void rejectionDuringRunningQuestionSurvivesAndResumesAfterQuestionFailure() throws Exception {
        Session user=cmp();Waiting w=waiting(user);Job question=ask(user,w.job().getIncidentId());claim(question).andExpect(status().isOk());
        decide(user,w.approvalId(),"REJECT",null).andExpect(status().isUnprocessableEntity());
        String resume=json(decide(user,w.approvalId(),"REJECT","이미 확인된 항목").andExpect(status().isOk()),"$.data.resumeJobId");
        assertThat(load(question.getJobId()).getStatus()).isEqualTo(JobStatus.RUNNING);
        getAs(user,"/api/approvals/{id}",w.approvalId()).andExpect(jsonPath("$.data.status").value("REJECTED"))
                .andExpect(jsonPath("$.data.decisionComment").value("이미 확인된 항목"));
        claim(load(resume)).andExpect(status().isConflict());
        event(question,"JOB_FAILED",Map.of("errorCode","MODEL_ERROR","message","질문 분석 실패")).andExpect(status().isOk());
        claim(load(resume)).andExpect(status().isOk());
        assertThat(load(resume).getRequestedBy()).isEqualTo(w.job().getRequestedBy());
    }

    @Test void concurrentDecisionsCreateOnlyOneResumeAndDoNotLoseTheQuestion() throws Exception {
        Session user=cmp();Waiting w=waiting(user);Job question=ask(user,w.job().getIncidentId());claim(question).andExpect(status().isOk());
        CountDownLatch go=new CountDownLatch(1);
        try(var pool=Executors.newFixedThreadPool(2)) {
            var approve=pool.submit(()->{go.await();return decide(user,w.approvalId(),"APPROVE",null).andReturn().getResponse().getStatus();});
            var reject=pool.submit(()->{go.await();return decide(user,w.approvalId(),"REJECT","중복 확인").andReturn().getResponse().getStatus();});
            go.countDown();assertThat(List.of(approve.get(20,TimeUnit.SECONDS),reject.get(20,TimeUnit.SECONDS))).containsExactlyInAnyOrder(200,409);
        }
        assertThat(jobs.findByIncidentIdOrderByCreatedAtDesc(question.getIncidentId()).stream().filter(j->j.getKind()==JobKind.RESUME)).hasSize(1);
        assertThat(load(question.getJobId()).getStatus()).isEqualTo(JobStatus.RUNNING);
    }

    @Test void questionCompletionRacingResumeClaimNeverCreatesTwoRunningJobs() throws Exception {
        Session user=cmp();Waiting w=waiting(user);Job question=ask(user,w.job().getIncidentId());claim(question).andExpect(status().isOk());
        String resume=json(decide(user,w.approvalId(),"APPROVE",null).andExpect(status().isOk()),"$.data.resumeJobId");
        CountDownLatch go=new CountDownLatch(1);
        try(var pool=Executors.newFixedThreadPool(2)) {
            var complete=pool.submit(()->{go.await();finish(question);return true;});
            var start=pool.submit(()->{go.await();return claim(load(resume)).andReturn().getResponse().getStatus();});
            go.countDown();assertThat(complete.get(20,TimeUnit.SECONDS)).isTrue();assertThat(start.get(20,TimeUnit.SECONDS)).isIn(200,409);
        }
        assertThat(load(question.getJobId()).getStatus()).isEqualTo(JobStatus.SUCCEEDED);
        if(load(resume).getStatus()==JobStatus.QUEUED) claim(load(resume)).andExpect(status().isOk());
        assertThat(jobs.findByIncidentIdOrderByCreatedAtDesc(question.getIncidentId()).stream().filter(j->j.getStatus()==JobStatus.RUNNING)).hasSize(1);
    }

    @Test void secondAgentApprovalIsRejectedButSameProposalKeyIsIdempotent() throws Exception {
        Session user=cmp();Waiting w=waiting(user);Job question=ask(user,w.job().getIncidentId());
        assertThat(proposal(w.job(),"proposal-"+w.job().getJobId())).isEqualTo(w.approvalId());
        internal("/internal/jobs/{id}/proposals",Map.of("actionType","CREATE_CHECK_REQUEST","payload",Map.of("ownerUserId","USR-ETCH-01","targetProcessCode","ETCH","question","추가 요청"),
                "idempotencyKey","another-"+question.getJobId()),question.getJobId()).andExpect(status().isConflict());
        finish(question);
        assertThat(load(w.job().getJobId()).getStatus()).isEqualTo(JobStatus.WAITING_APPROVAL);
    }

    @Test void selectedResultsCanStartFollowupWhileApprovalIsWaiting() throws Exception {
        Session user=cmp();Waiting w=waiting(user);String id=w.job().getIncidentId();
        String action=json(postAs(user,"/api/incidents/{id}/actions",Json.write(Map.of("actionType","INSPECTION","description","직접 실측값 확인",
                "performedAt","2026-10-23T01:00:00Z","confirmedByUser",true,"expectedIncidentVersion",incidentVersion(user,id))),id).andExpect(status().isCreated()),"$.data.actionId");
        postAs(user,"/api/incidents/{id}/followup-analysis-jobs",Json.write(Map.of("sourceRefs",List.of(Map.of("type","ACTION","id",action)),"expectedIncidentVersion",incidentVersion(user,id))),id)
                .andExpect(status().isAccepted()).andExpect(jsonPath("$.data.kind").value("DEEP"));
        getAs(user,"/api/incidents/{id}",id).andExpect(jsonPath("$.data.waitingJobs[0].waitingRef.id").value(w.approvalId()));
    }

    @Test void summaryQueueDoesNotBlockInvestigationAndCanStartIndependently() throws Exception {
        Session user=cmp();Job basic=create();String id=basic.getIncidentId();
        String summary=tx.execute(status->{var incident=incidents.findById(id).orElseThrow();
            return jobService.enqueue(incident,JobKind.SUMMARY,"USR-CMP-01",null,null,Map.of("summaryRef",Map.of("reportId","queue-only-test")),null,null).getJobId();});
        Job question=ask(user,id);claim(question).andExpect(status().isOk());
        tx.executeWithoutResult(status->{var incident=incidents.findById(id).orElseThrow();assertThat(jobService.canStart(load(summary),incident)).isTrue();});
        assertThat(incidents.findById(id).orElseThrow().getActiveJobId()).isEqualTo(question.getJobId());
    }
}
