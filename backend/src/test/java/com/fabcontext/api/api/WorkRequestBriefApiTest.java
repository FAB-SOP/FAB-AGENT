package com.fabcontext.api.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import com.fabcontext.api.common.Ids;
import com.fabcontext.api.common.Json;
import com.fabcontext.api.domain.analysis.AnalysisResult;
import com.fabcontext.api.domain.analysis.AnalysisResultRepository;
import com.fabcontext.api.support.ApiTestSupport;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

class WorkRequestBriefApiTest extends ApiTestSupport {
 @Autowired AnalysisResultRepository results;
 @Autowired JdbcTemplate jdbc;
 private String source(NewIncident incident) {
  return results.saveAndFlush(AnalysisResult.builder().resultId(Ids.next("res")).incidentId(incident.incidentId()).jobId(incident.jobId())
   .kind("BASIC").threadId("test-brief").summary("보내지 않을 내부 원인 요약")
   .observedFacts(List.of(Map.of("statement","센서 실측과 설정 기록을 확인했습니다","evidenceIds",List.of("E-CONTEXT"))))
   .evidenceRefs(List.of(Map.of("evidenceId","E-CONTEXT","type","METROLOGY")))
   .candidateCauses(List.of(Map.of("causeId","c1","hypothesis","보내지 않을 원인 후보")))
   .createdAt(Instant.now()).build()).getResultId();
 }
 private String propose(Session user,String incident,String source,String operation) throws Exception {
  return json(postAs(user,"/api/incidents/{id}/work-items",Json.write(Map.of(
   "operationCode",operation,"title","설정 기록 확인","reason","후속 확인에 필요한 요청 사유","sourceResultId",source,
   "evidenceIds",List.of("E-CONTEXT"),"expectedIncidentVersion",incidentVersion(user,incident))),incident)
   .andExpect(status().isCreated()),"$.data.taskId");
 }
 private void changeOriginal(String source) {
  jdbc.update("update analysis_result set observed_facts=cast(? as jsonb) where result_id=?",Json.write(List.of(Map.of("statement","나중에 변경한 관측 내용","evidenceIds",List.of("E-CONTEXT")))),source);
 }
 @Test void managerApprovalInboxKeepsOriginalBriefAfterSourceChanges() throws Exception {
  Session sender=cmp(), manager=manager();
  var incident=newIncident(sender,false); String source=source(incident);
  String task=propose(sender,incident.incidentId(),source,"CHANGE_SETPOINT");
  postAs(sender,"/api/work-items/{id}/adoptions","{\"expectedVersion\":1}",task)
   .andExpect(status().isOk()).andExpect(jsonPath("$.data.status").value("WAITING_MANAGER"))
   .andExpect(jsonPath("$.data.brief.reason").value("후속 확인에 필요한 요청 사유"))
   .andExpect(jsonPath("$.data.brief.suspectedCauses",hasSize(0)));
  changeOriginal(source);
  var inbox=getAs(manager,"/api/work-items?box=approval").andExpect(status().isOk())
   .andExpect(jsonPath("$.data.items[?(@.taskId=='"+task+"')].brief.relatedRecords[*].summary",hasItem("센서 실측과 설정 기록을 확인했습니다")));
  String body=inbox.andReturn().getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
  assertThat(body).doesNotContain("나중에 변경한 관측 내용","보내지 않을 내부 원인 요약","보내지 않을 원인 후보");
 }
 @Test void teamRequestReceivesSameFrozenBriefWithoutIncidentAccess() throws Exception {
  Session sender=cmp(), recipient=login("etch.lee");
  var incident=newIncident(sender,false); String source=source(incident);
  String task=propose(sender,incident.incidentId(),source,"UPSTREAM_CHECK");
  String request=json(postAs(sender,"/api/work-items/{id}/adoptions",
   "{\"expectedVersion\":1,\"ownerUserId\":\"USR-ETCH-01\",\"targetProcessCode\":\"ETCH\"}",task)
   .andExpect(status().isOk()),"$.data.requestId");
  changeOriginal(source);
  var detail=getAs(recipient,"/api/check-requests/{id}",request).andExpect(status().isOk())
   .andExpect(jsonPath("$.data.brief.reason").value("후속 확인에 필요한 요청 사유"))
   .andExpect(jsonPath("$.data.brief.relatedRecords[*].summary",hasItem("센서 실측과 설정 기록을 확인했습니다")))
   .andExpect(jsonPath("$.data.brief.askFor",contains("설정 기록 확인")))
   .andExpect(jsonPath("$.data.brief.suspectedCauses",hasSize(0)));
  String body=detail.andReturn().getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
  assertThat(body).doesNotContain("나중에 변경한 관측 내용","보내지 않을 원인 후보","보내지 않을 내부 원인 요약");
  getAs(recipient,"/api/incidents/{id}/analysis-results",incident.incidentId()).andExpect(status().isForbidden());
  getAs(recipient,"/api/incidents/{id}",incident.incidentId()).andExpect(status().isForbidden());
 }
 @Autowired com.fabcontext.api.domain.processdata.ProcessDataRepositories.RouteRunRepository runs;
 @Autowired com.fabcontext.api.domain.processdata.ProcessEvidenceService evidence;
 @Test void actualMetrologyAndRelevantUnconfirmedContextAreDelivered() throws Exception {
  Session sender=cmp(), recipient=login("etch.lee");
  var incident=newIncident(sender,false);
  String basic=json(getAs(sender,"/api/incidents/{id}/analysis-results",incident.incidentId()),"$.data.resultId");
  var original=results.findById(basic).orElseThrow();
  var refs=new java.util.ArrayList<>(original.getEvidenceRefs());
  refs.add(Map.of("evidenceId","ev:change:DEMO-CHANGE-E02","type","CHANGE","ref","DEMO-CHANGE-E02","label","정비 기록"));
  String source=results.saveAndFlush(AnalysisResult.builder().resultId(Ids.next("res")).incidentId(incident.incidentId()).jobId(incident.jobId())
   .kind("BASIC").createdAt(Instant.now()).observedFacts(original.getObservedFacts()).evidenceRefs(refs)
   .candidateCauses(List.of(
    Map.of("causeId","cause-etch","processCode","ETCH","hypothesis","챔버 세정 이후 상태 변화 가능성","supportingEvidenceIds",List.of("ev:run:RUN-W-CURRENT-009-3-ETCH"),"nextChecks",List.of("시즈닝 후 모니터 결과 확인")),
    Map.of("causeId","cause-cmp","processCode","CMP","hypothesis","공유 대상이 아닌 공정 후보","supportingEvidenceIds",List.of("ev:run:RUN-W-CURRENT-009-3-CMP"))
   )).build()).getResultId();
  String task=json(postAs(sender,"/api/incidents/{id}/work-items",Json.write(Map.of(
   "operationCode","UPSTREAM_CHECK","title","앞 공정 정비 기록 확인","reason","계측 결과를 바탕으로 추가 확인 필요","sourceResultId",source,
   "sourceCauseId","cause-etch","expectedIncidentVersion",incidentVersion(sender,incident.incidentId()))),incident.incidentId()),"$.data.taskId");
  String request=json(postAs(sender,"/api/work-items/{id}/adoptions","{\"expectedVersion\":1,\"ownerUserId\":\"USR-ETCH-01\",\"targetProcessCode\":\"ETCH\"}",task),"$.data.requestId");
  var raw=evidence.runEvidence(runs.findByRunId("RUN-W-CURRENT-009-3-ETCH").orElseThrow(),evidence.defaultAsOf());
  var detail=getAs(recipient,"/api/check-requests/{id}",request).andExpect(status().isOk())
   .andExpect(jsonPath("$.data.brief.deviations[?(@.runId=='RUN-W-CURRENT-009-3-ETCH' && @.metric=='removedA')].value",contains(raw.get("amountA"))))
   .andExpect(jsonPath("$.data.brief.deviations[?(@.runId=='RUN-W-CURRENT-009-3-ETCH' && @.metric=='removedA')].z",contains(raw.get("amountZSamePm"))))
   .andExpect(jsonPath("$.data.brief.deviations[?(@.metric=='outputMeanA')].value",hasSize(1)))
   .andExpect(jsonPath("$.data.brief.suspectedCauses",hasSize(1)))
   .andExpect(jsonPath("$.data.brief.suspectedCauses[0].confirmed").value(false))
   .andExpect(jsonPath("$.data.brief.askFor",hasItem("시즈닝 후 모니터 결과 확인")))
   .andExpect(jsonPath("$.data.brief.relatedRecords[*].summary",hasItem(containsString("CHAMBER_SERVICE"))));
  assertThat(detail.andReturn().getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8)).doesNotContain("공유 대상이 아닌 공정 후보");
 }

}
