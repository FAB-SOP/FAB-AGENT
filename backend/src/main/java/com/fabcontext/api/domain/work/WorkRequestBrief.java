package com.fabcontext.api.domain.work;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.Objects;
import com.fabcontext.api.domain.incident.Incident;
import com.fabcontext.api.domain.processdata.ProcessEvidenceService;
import com.fabcontext.api.domain.processdata.ProcessDataRepositories.RouteRunRepository;
import com.fabcontext.api.domain.processdata.ProcessDataRepositories.ChangeRecordRepository;
import com.fabcontext.api.domain.processdata.RouteRun;
import com.fabcontext.api.domain.processdata.ChangeRecord;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import com.fabcontext.api.domain.analysis.AnalysisResult;
import com.fabcontext.api.domain.checkrequest.RequestBrief;

/** 발송할 업무의 관측·실제 계측·관련 기록을 보존한다. 전체 후보 목록이나 수치 추정은 전달하지 않는다. */
@Component
@RequiredArgsConstructor
final class WorkRequestBrief {
 private final RouteRunRepository runs;
 private final ProcessEvidenceService evidence;
 private final ChangeRecordRepository changes;
 private static final int MAX_FACTS=6;
 private static final int MAX_TEXT=800;

 static Map<String,Object> build(WorkItem work, AnalysisResult source) {
  Set<String> known=source.getEvidenceRefs().stream().map(ref->text(ref.get("evidenceId")))
   .filter(id->!id.isBlank()).collect(Collectors.toSet());
  Set<String> allowed=work.getEvidenceIds().isEmpty()?known:work.getEvidenceIds().stream().filter(known::contains).collect(Collectors.toSet());
  List<Map<String,Object>> records=new ArrayList<>();
  records.add(Map.of("summary","조사 기준: 분석 결과 "+source.getResultId()+" / 관측 사실과 등록 근거 확인"));
  for(Map<String,Object> fact:source.getObservedFacts()) {
   if(records.size()>MAX_FACTS) break;
   String statement=text(fact.get("statement"));
   if(statement.isBlank()) statement=text(fact.get("label"));
   List<String> ids=ids(fact);
   // 복합 관측 문장에서 일부 비선택 근거의 내용을 함께 유출하지 않는다.
   if(statement.isBlank() || ids.isEmpty() || !allowed.containsAll(ids)) continue;
   Map<String,Object> record=new LinkedHashMap<>();
   record.put("evidenceId",ids.getFirst());
   record.put("summary",clip(statement));
   records.add(record);
  }
  if(records.size()==1) records.add(Map.of("summary","이 업무에 연결해 전달할 관측 요약이 없습니다. 아래 요청 사항을 확인해 주세요."));
  return RequestBrief.validate(Map.of(
   "reason",work.getReason(),
   "deviations",List.of(),
   "suspectedCauses",List.of(),
   "relatedRecords",records,
   "askFor",List.of(work.getTitle())
  ));
 }

 @SuppressWarnings("unchecked")
 Map<String,Object> forDelivery(WorkItem work,AnalysisResult source,Incident incident,String targetProcess,String targetEquipment) {
  Map<String,Object> brief=new LinkedHashMap<>(build(work,source));
  Set<String> known=source.getEvidenceRefs().stream().map(ref->text(ref.get("evidenceId"))).filter(id->!id.isBlank()).collect(Collectors.toSet());
  Set<String> allowed=work.getEvidenceIds().isEmpty()?known:work.getEvidenceIds().stream().filter(known::contains).collect(Collectors.toSet());
  Instant asOf=evidence.defaultAsOf();
  List<Map<String,Object>> rows=new ArrayList<>();
  List<Map<String,Object>> records=new ArrayList<>((List<Map<String,Object>>)brief.get("relatedRecords"));
  Set<String> usedRuns=new LinkedHashSet<>();
  List<RouteRun> path=runs.findByWaferIdInOrderByWaferIdAscSequenceNoAsc(incident.getWaferIds());
  int maxSequence=ProcessEvidenceService.PROCESS_ORDER.indexOf(incident.getProcessCode())+1;
  Set<String> equipment=path.stream().filter(r->r.getSequenceNo()<=maxSequence).map(RouteRun::getEquipmentId).collect(Collectors.toSet());
  for(Map<String,Object> ref:source.getEvidenceRefs()) {
   String id=text(ref.get("evidenceId"));
   if(!allowed.contains(id)) continue;
   String runId=id.startsWith("ev:run:")?id.substring(7):id.startsWith("E-RUN-")?id.substring(6):"METROLOGY".equals(ref.get("type"))?text(ref.get("ref")):"";
   if(runId.startsWith("RUN-") && usedRuns.add(runId)) {
    path.stream().filter(r->runId.equals(r.getRunId()) && r.getSequenceNo()<=maxSequence)
     .filter(r->r.getEndedAt()!=null && !r.getEndedAt().isAfter(asOf)).findFirst().ifPresent(run->{
      Map<String,Object> values=evidence.runEvidence(run,asOf);
      if(Boolean.TRUE.equals(values.get("available")) && rows.size()<6) {
       Map<?,?> baselines=values.get("baselineAmount") instanceof Map<?,?> m?m:Map.of();
       Map<?,?> pm=baselines.get("samePm") instanceof Map<?,?> m?m:Map.of();
       Map<String,Object> row=new LinkedHashMap<>();
       row.put("runId",run.getRunId()); row.put("equipmentId",run.getEquipmentId()); row.put("processCode",run.getProcessCode());
       row.put("metric","CVD".equals(run.getProcessCode())?"depositedA":"removedA");
       row.put("value",values.get("amountA")); row.put("unit","Å"); row.put("baselineMean",pm.get("mean"));
       row.put("z",values.get("amountZSamePm")); row.put("threshold",values.get("normalVariationSigma")); row.put("thresholdKind","SAME_PM_BASELINE");
       row.put("withinNormal",values.get("amountZSamePm")==null?null:values.get("amountWithinNormalVariation"));
       rows.add(row);
       // 현재 공정의 출고 두께도 보존해 실제 관리한계 이탈 값을 함께 확인한다.
       if(run.getProcessCode().equals(incident.getProcessCode()) && rows.size()<6 && values.get("limit") instanceof Map<?,?> limit && values.get("r1") instanceof Map<?,?> r1) {
        Map<String,Object> output=new LinkedHashMap<>(row);
        output.put("metric","outputMeanA"); output.put("value",values.get("outputMeanA")); output.put("baselineMean",limit.get("center")); output.put("z",null);
        output.put("lcl",limit.get("lcl")); output.put("ucl",limit.get("ucl")); output.put("thresholdKind","CONTROL_LIMIT");
        output.put("threshold","BELOW_LCL".equals(r1.get("side"))?limit.get("lcl"):limit.get("ucl")); output.put("withinNormal",!Boolean.TRUE.equals(r1.get("outside")));
        rows.add(output);
       }
      }
     });
   }
   String changeId=id.startsWith("ev:change:")?id.substring(10):"CHANGE".equals(ref.get("type"))?text(ref.get("ref")):"";
   List<ChangeRecord> selected=new ArrayList<>();
   if(!changeId.isBlank()) changes.findById(changeId).ifPresent(selected::add);
   if(selected.isEmpty() && "CHANGE".equals(ref.get("type")) && equipment.contains(changeId))
    changes.findAll().stream().filter(c->changeId.equals(c.getEquipmentId())).limit(6).forEach(selected::add);
   selected.stream().filter(c->equipment.contains(c.getEquipmentId()) && targetEquipment.equals(c.getEquipmentId()))
    .filter(c->c.getRecordedAt()==null || !c.getRecordedAt().isAfter(asOf))
    .filter(c->c.getOccurredAt()==null || !c.getOccurredAt().isAfter(asOf)).limit(3)
    .forEach(c->{ if(records.size()<12) records.add(Map.of("evidenceId",id,"summary",clip(Objects.toString(c.getOccurredAt(),"")+" "+c.getEquipmentId()+" "+c.getChangeType()+": "+Objects.toString(c.getSummary(),"기록 내용 없음")))); });
   if(records.size()<12 && List.of("PLAYBOOK","DOCUMENT","SOURCE").contains(text(ref.get("type"))) && !text(ref.get("label")).isBlank())
    records.add(Map.of("evidenceId",id,"summary","참조 문서: "+clip(text(ref.get("label")))));
  }
  List<Map<String,Object>> relevant=relevantCandidates(work,source,targetProcess,allowed);
  brief.put("suspectedCauses",relevant.stream().limit(3).map(c->Map.<String,Object>of("causeId",text(c.get("causeId")),"candidate",clip(text(c.getOrDefault("hypothesis",c.get("cause")))),"confirmed",false)).toList());
  Set<String> checks=new LinkedHashSet<>(); checks.add(work.getTitle());
  for(Map<String,Object> c:relevant) {
   if(c.get("nextChecks") instanceof Collection<?> items) items.stream().filter(String.class::isInstance).map(String.class::cast).filter(v->!v.isBlank()).map(WorkRequestBrief::clip).forEach(checks::add);
  }
  brief.put("askFor",checks.stream().limit(6).toList());
  brief.put("deviations",rows); brief.put("relatedRecords",records);
  return RequestBrief.validate(brief);
 }

 static List<Map<String,Object>> relevantCandidates(WorkItem work,AnalysisResult source,String target,Set<String> allowed) {
  return source.getCandidateCauses().stream().filter(c->!text(c.getOrDefault("hypothesis",c.get("cause"))).isBlank())
   .filter(c->target.equals(c.get("processCode")))
   .filter(c->work.getSourceCauseId()!=null?work.getSourceCauseId().equals(c.get("causeId")):
    c.get("supportingEvidenceIds") instanceof Collection<?> ids && ids.stream().anyMatch(allowed::contains)).limit(3).toList();
 }
 private static List<String> ids(Map<String,Object> fact) {
  if(fact.get("evidenceIds") instanceof Collection<?> ids)
   return ids.stream().filter(String.class::isInstance).map(String.class::cast).filter(id->!id.isBlank()).distinct().toList();
  String id=text(fact.get("evidenceId"));
  return id.isBlank()?List.of():List.of(id);
 }
 private static String text(Object value) { return value instanceof String s?s:""; }
 private static String clip(String value) {
  if(value.length()<=MAX_TEXT) return value;
  int end=MAX_TEXT;
  while(end>0 && !Character.isWhitespace(value.charAt(end))) end--;
  if(end==0) return "긴 관측 요약은 생략했습니다. 요청자에게 상세 내용을 확인해 주세요.";
  return value.substring(0,end)+" … [이하 생략]";
 }
}
