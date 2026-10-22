package com.fabcontext.api.domain.work;

import static org.assertj.core.api.Assertions.assertThat;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import com.fabcontext.api.common.Json;
import com.fabcontext.api.domain.analysis.AnalysisResult;

class WorkRequestBriefTest {
 private WorkItem work(List<String> ids) {
  return new WorkItem("task","inc","CMP","CMP-PM3",WorkOperation.UPSTREAM_CHECK,"설정 기록 확인","요청 사유","res",null,ids,"user",Instant.EPOCH);
 }
 private AnalysisResult source(List<Map<String,Object>> facts) {
  return AnalysisResult.builder().resultId("res").incidentId("inc").summary("내부 원인 요약")
   .observedFacts(facts).evidenceRefs(List.of(Map.of("evidenceId","E-1"),Map.of("evidenceId","E-2")))
   .candidateCauses(List.of(Map.of("hypothesis","공유하지 않을 원인 후보"))).build();
 }
 @Test void sharesSelectedObservationsWithoutCandidatesOrInternalSummary() {
  var brief=WorkRequestBrief.build(work(List.of("E-1")),source(List.of(
   Map.of("statement","공유할 실측 기록 확인","evidenceIds",List.of("E-1")),
   Map.of("statement","선택 안 한 내부 기록","evidenceIds",List.of("E-2")),
   Map.of("statement","근거가 섞인 내부 기록","evidenceIds",List.of("E-1","E-2"))
  )));
  String json=Json.write(brief);
  assertThat(json).contains("공유할 실측 기록 확인").doesNotContain("선택 안 한 내부 기록","근거가 섞인 내부 기록","공유하지 않을 원인 후보","내부 원인 요약");
  assertThat(brief.get("suspectedCauses")).isEqualTo(List.of());
  assertThat(brief.get("askFor")).isEqualTo(List.of("설정 기록 확인"));
 }
 @Test void absentObservationsAreExplicitRatherThanInvented() {
  String json=Json.write(WorkRequestBrief.build(work(List.of()),source(List.of())));
  assertThat(json).contains("전달할 관측 요약이 없습니다").doesNotContain("공유하지 않을 원인 후보");
 }
 @Test void boundedBriefUsesOnlyRegisteredEvidence() {
  List<Map<String,Object>> facts=IntStream.range(0,20).mapToObj(i->Map.<String,Object>of("statement","관측 "+i,"evidenceIds",List.of("E-1"))).toList();
  var brief=WorkRequestBrief.build(work(List.of()),source(facts));
  assertThat((List<?>)brief.get("relatedRecords")).hasSize(7); // 출처 1 + 관측 최대 6
  assertThat(Json.write(brief)).doesNotContain("관측 6");
 }
 @Test void onlyRequestLinkedCandidatesInTargetProcessAreShared() {
  var work=work(List.of("E-1"));
  var source=AnalysisResult.builder().candidateCauses(List.of(
   Map.of("causeId","c-etch","processCode","ETCH","hypothesis","챔버 상태 변화 가능성","supportingEvidenceIds",List.of("E-1")),
   Map.of("causeId","c-cmp","processCode","CMP","hypothesis","다른 공정 후보","supportingEvidenceIds",List.of("E-1")),
   Map.of("causeId","c-other","processCode","ETCH","hypothesis","다른 근거의 후보","supportingEvidenceIds",List.of("E-2"))
  )).build();
  assertThat(WorkRequestBrief.relevantCandidates(work,source,"ETCH",java.util.Set.of("E-1")))
   .extracting(c->c.get("causeId")).containsExactly("c-etch");
 }

}
