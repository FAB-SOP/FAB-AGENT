package com.fabcontext.api.domain.job;

import static org.assertj.core.api.Assertions.*;
import java.time.Instant;
import java.util.Map;
import java.util.List;
import org.junit.jupiter.api.Test;

class JobInputContractTest {
    private Job job(JobKind kind, Map<String,Object> input) {
        return Job.builder().jobId("job_test").incidentId("inc_test").kind(kind).input(input).createdAt(Instant.now()).build();
    }
    @Test void newJobsFreezeFlowAndRejectKindPurposeMismatch() {
        var contract = new JobInputContract("MANUAL");
        assertThat(contract.initialize(JobKind.BASIC, Map.of(), null)).containsEntry("purpose","BASIC").containsEntry("replyFlow","MANUAL");
        assertThatThrownBy(() -> contract.initialize(JobKind.DEEP, Map.of("purpose","BASIC"), null)).hasMessageContaining("목적");
    }
    @Test void resumePreservesOldThreadVersionEvenWhenDefaultChanges() {
        var contract = new JobInputContract("MANUAL");
        assertThat(contract.initialize(JobKind.RESUME, Map.of(), job(JobKind.DEEP,Map.of())))
                .doesNotContainKey("replyFlow");
        assertThat(contract.initialize(JobKind.RESUME, Map.of(), job(JobKind.DEEP,Map.of("replyFlow","MANUAL"))))
                .containsEntry("replyFlow","MANUAL");
    }
    @Test void followupProjectionDropsInternalFieldsAndKeepsOnlySelectedSnapshot() {
        var contract = new JobInputContract("WAIT");
        var input = contract.initialize(JobKind.DEEP,Map.of("analysisRequestId","arq_1","followupQuestion","재분석",
                "interruptId","secret", "selectedInputs",List.of(Map.of("sourceRef",Map.of("type","REPLY","id","rpl_1"),
                        "incidentId","inc_test","evidenceId","E-REQ-req_1","content","실측 결과","authorUserId","USR_1","createdAt","2026-10-23T01:00:00Z"))),null);
        var output = contract.publicInput(job(JobKind.DEEP,input));
        assertThat(output).containsEntry("purpose","FOLLOWUP").doesNotContainKeys("interruptId","selectedInputs");
        var selected = (List<?>) ((Map<?,?>)output.get("followup")).get("selectedInputs");
        assertThat(((Map<?,?>)selected.getFirst()).containsKey("incidentId")).isFalse();
    }
    @Test void unsetBackendFlowDoesNotOverrideExistingWorkerConfiguration() {
        var contract=new JobInputContract("");
        var input=contract.initialize(JobKind.DEEP,Map.of(),null);
        assertThat(input).doesNotContainKey("replyFlow");
        assertThat(contract.publicInput(job(JobKind.DEEP,input))).containsEntry("purpose","QUESTION").doesNotContainKey("replyFlow");
    }
    @Test void resumeDoesNotExposeParentPurposeOrPrivateResumeInput() {
        var result = new JobInputContract("WAIT").publicInput(job(JobKind.RESUME,Map.of("purpose","FOLLOWUP","interruptId","int_1","replyFlow","MANUAL")));
        assertThat(result).containsOnlyKeys("replyFlow");
    }
}
