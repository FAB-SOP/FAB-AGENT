package com.fabcontext.api.api;

import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.assertj.core.api.Assertions.*;
import java.util.Map;
import com.fabcontext.api.common.Json;
import com.fabcontext.api.domain.job.*;
import com.fabcontext.api.support.ApiTestSupport;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;

/** 시뮬레이터도 지연 재개를 놓치지 않고 현재 질문 종료 후 자동으로 깨운다. */
@TestPropertySource(properties={"fab.agent.step-delay-ms=150","spring.jpa.properties.hibernate.default_schema=backend_resume_sim","spring.datasource.hikari.schema=backend_resume_sim"})
class ApprovalResumeSimulatorApiTest extends ApiTestSupport {
    @Autowired JobRepository jobs;
    @Test void queuedResumeStartsAfterTheQuestionWithoutAnotherUserAction() throws Exception {
        Session user=cmp();String id=newIncident(user,false).incidentId();
        String first=json(postAs(user,"/api/incidents/{id}/questions",Json.write(Map.of("question","앞 공정 확인 요청을 제안해 주세요.","scope","UPSTREAM",
                "expectedIncidentVersion",incidentVersion(user,id))),id).andExpect(status().isAccepted()),"$.data.jobId");
        awaitJob(user,first,"WAITING_APPROVAL");
        String approval=jobs.findById(first).orElseThrow().getWaitingRefId();
        String question=json(postAs(user,"/api/incidents/{id}/questions",Json.write(Map.of("question","현재 공정 근거만 설명해 주세요.","scope","CURRENT",
                "expectedIncidentVersion",incidentVersion(user,id))),id).andExpect(status().isAccepted()),"$.data.jobId");
        String resume=json(postAs(user,"/api/approvals/{id}/decisions","{\"decision\":\"APPROVE\",\"expectedVersion\":1}",approval)
                .andExpect(status().isOk()),"$.data.resumeJobId");
        awaitJob(user,question,"SUCCEEDED");
        awaitJob(user,resume,"WAITING_REPLY");
        assertThat(jobs.findById(resume).orElseThrow().getParentJobId()).isEqualTo(first);
        getAs(user,"/api/incidents/{id}",id).andExpect(jsonPath("$.data.activeJob.jobId").value(resume))
                .andExpect(jsonPath("$.data.waitingJobs[0].jobId").value(resume))
                .andExpect(jsonPath("$.data.status").value("WAITING_REPLY"));
    }
}
