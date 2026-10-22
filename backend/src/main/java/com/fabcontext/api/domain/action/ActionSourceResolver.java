package com.fabcontext.api.domain.action;

import java.util.LinkedHashMap;
import java.util.Map;

import com.fabcontext.api.common.ApiException;
import com.fabcontext.api.domain.action.ActionDtos.SourceRef;
import com.fabcontext.api.domain.analysis.AnalysisResultRepository;
import com.fabcontext.api.domain.approval.ApprovalRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/** 같은 사건에 저장된 항목만 연결하고 제목·내용은 서버 원본에서 가져온다. */
@Component
@RequiredArgsConstructor
class ActionSourceResolver {
 private final AnalysisResultRepository results;
 private final ApprovalRepository approvals;
 private final com.fabcontext.api.domain.work.WorkItemRepository workItems;

 Map<String, Object> resolve(String incidentId, SourceRef ref, String actorId) {
  if (ref == null) return null;
  Map<String, Object> snapshot = new LinkedHashMap<>();
  snapshot.put("type", ref.type());
  snapshot.put("refId", ref.refId());
  if ("WORK_ITEM".equals(ref.type())) {
   var task=workItems.findById(ref.refId()).filter(w->incidentId.equals(w.getIncidentId())).orElseThrow(ActionSourceResolver::invalid);
   if(!actorId.equals(task.getAssigneeUserId())||ref.itemId()!=null||!java.util.Set.of("READY","APPROVED","COMPLETED").contains(task.getStatus().name())) throw invalid();
   snapshot.put("title",task.getTitle());snapshot.put("operationCode",task.getOperationCode().name());snapshot.put("route",task.getRoute().name());
   if(task.getApproverUserId()!=null) snapshot.put("approverUserId",task.getApproverUserId());
   snapshot.put("managerApproved",task.getRoute()==com.fabcontext.api.domain.work.WorkOperation.Route.MANAGER_APPROVAL&&(task.getStatus()==com.fabcontext.api.domain.work.WorkItem.Status.APPROVED||task.getStatus()==com.fabcontext.api.domain.work.WorkItem.Status.COMPLETED));
   return snapshot;
  }
  if ("PROPOSAL".equals(ref.type())) {
   var approval = approvals.findById(ref.refId()).filter(a -> incidentId.equals(a.getIncidentId()))
    .orElseThrow(ActionSourceResolver::invalid);
   if (ref.itemId() != null) throw invalid();
   snapshot.put("title", approval.getPayload().getOrDefault("question", approval.getActionType().name()));
   snapshot.put("rationale", approval.getRationale() == null ? "" : approval.getRationale());
   snapshot.put("proposalStatusAtRecord", approval.getStatus().name());
   snapshot.put("actionType", approval.getActionType().name());
   snapshot.put("payload", new LinkedHashMap<>(approval.getPayload()));
   return snapshot;
  }
  var result = results.findById(ref.refId()).filter(r -> incidentId.equals(r.getIncidentId()))
   .orElseThrow(ActionSourceResolver::invalid);
  Map<String, Object> item;
  if ("CAUSE".equals(ref.type())) {
   item = result.getCandidateCauses().stream().filter(c -> ref.itemId() != null && ref.itemId().equals(c.get("causeId")))
    .findFirst().orElseThrow(ActionSourceResolver::invalid);
  } else if ("NEXT_ACTION".equals(ref.type())) {
   try { item = result.getNextActions().get(Integer.parseInt(ref.itemId())); }
   catch (RuntimeException e) { throw invalid(); }
  } else { throw invalid(); }
  snapshot.put("itemId", ref.itemId());
  snapshot.put("title", item.getOrDefault("hypothesis", item.getOrDefault("cause", item.getOrDefault("label", "분석 제안"))));
  snapshot.put("analysisKind", result.getKind());
  snapshot.put("analysisCreatedAt", result.getCreatedAt().toString());
  snapshot.put("details", new LinkedHashMap<>(item));
  return snapshot;
 }
 private static ApiException invalid() {
  return ApiException.unprocessable("ACTION_SOURCE_INVALID", "같은 사건에 저장된 분석 항목 또는 제안만 연결할 수 있습니다.");
 }
}
