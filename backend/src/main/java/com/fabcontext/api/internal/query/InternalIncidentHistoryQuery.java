package com.fabcontext.api.internal.query;

import java.time.Clock;
import java.time.Instant;
import java.util.*;

import com.fabcontext.api.common.ApiResponse;
import com.fabcontext.api.common.Json;
import com.fabcontext.api.domain.analysis.AnalysisResult;
import com.fabcontext.api.domain.analysis.AnalysisResultRepository;
import com.fabcontext.api.domain.approval.ApprovalRepository;
import com.fabcontext.api.domain.approval.ApprovalStatus;
import com.fabcontext.api.domain.checkrequest.CheckRequestRepositories.*;
import com.fabcontext.api.domain.action.IncidentActionRepository;
import com.fabcontext.api.internal.view.InternalViews;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Agent의 6,000자 도구 한도 안에 넣는 사건 요약. 전체 원문/범용 페이지 API를 추가하지 않는다. */
@Service
@RequiredArgsConstructor
@Transactional(readOnly=true)
public class InternalIncidentHistoryQuery {
    private final InternalIncidentQuery incidents;
    private final AnalysisResultRepository results;
    private final ApprovalRepository approvals;
    private final CheckRequestRepository requests;
    private final ReplyRepository replies;
    private final IncidentActionRepository actions;
    private final InternalViews views;
    private final Clock clock;

    public Map<String,Object> history(String id) {
        var incident = incidents.resolve(id);
        String incidentId = incident.getIncidentId();
        var data = new LinkedHashMap<String,Object>();
        data.put("incidentId",incidentId); data.put("asOf",Instant.now(clock).toString());
        data.put("latestCauseAnalysis",results.latestCauseAnalysis(incidentId).map(this::analysisSummary).orElse(null));
        var approvalRows = approvals.findByIncidentIdOrderByCreatedAtDesc(incidentId);
        var pending = approvalRows.stream().filter(a -> a.getStatus()==ApprovalStatus.PENDING
                && (a.getExpiresAt()==null || a.getExpiresAt().isAfter(Instant.now(clock)))).toList();
        data.put("pendingApproval", pending.isEmpty()?null:row("approvalId",pending.getFirst().getApprovalId(),"jobId",pending.getFirst().getJobId(),
                "actionType",com.fabcontext.api.contract.ActionTypeNames.internal(pending.getFirst().getActionType()),"targetProcessCode",pending.getFirst().getPayload().get("targetProcessCode"),
                "createdAt",pending.getFirst().getCreatedAt().toString(),"sourceResultId",pending.getFirst().getSourceResultId()));
        data.put("pendingApprovalCount",pending.size());
        List<Map<String,Object>> requestItems = new ArrayList<>();
        for(var r:requests.findByIncidentIdOrderByCreatedAtAsc(incidentId)) requestItems.add(row("requestId",r.getRequestId(),"origin",views.requestOrigin(r),
                "requestType",r.getRequestType(),"targetProcessCode",r.getTargetProcessCode(),"status",r.getStatus().name(),"createdAt",r.getCreatedAt().toString(),
                "decisionAt",iso(r.getDecisionAt()),"decisionComment",shortText(r.getDecisionComment(),200),"repliedAt",iso(r.getRepliedAt()),
                "withdrawnAt",iso(r.getWithdrawnAt()),"withdrawReason",shortText(r.getWithdrawReason(),200)));
        Collections.reverse(requestItems);
        List<Map<String,Object>> approvalItems = new ArrayList<>();
        for(var a:approvalRows) approvalItems.add(row("approvalId",a.getApprovalId(),"actionType",com.fabcontext.api.contract.ActionTypeNames.internal(a.getActionType()),
                "targetProcessCode",a.getPayload().get("targetProcessCode"),"status",a.getStatus().name(),"createdAt",a.getCreatedAt().toString(),
                "decidedAt",iso(a.getDecidedAt()),"comment",shortText(a.getDecisionComment(),200),"sourceResultId",a.getSourceResultId()));
        List<Map<String,Object>> items = new ArrayList<>();
        var timeline=incident.getTimeline();
        for(int n=0;n<timeline.size();n++) {
            var t=timeline.get(n);
            if("ANALYSIS_COMPLETED".equals(t.getType()) || "REQUEST_REPLIED".equals(t.getType()) || "ACTION_RECORDED".equals(t.getType())) continue;
            items.add(row("recordId","timeline-"+incidentId+"-"+n,"at",iso(t.getAt()),"type",t.getType(),"summary",shortText(t.getMessage(),200),
                    "sourceRef",row("type",t.getRefType(),"id",t.getRefId())));
        }
        for(var r:results.findByIncidentIdOrderByCreatedAtDesc(incidentId)) items.add(row("recordId","result-"+r.getResultId(),"at",iso(r.getCreatedAt()),
                "type",r.getResultKind(),"summary",shortText(r.getAnswer()==null?r.getSummary():r.getAnswer(),200),"sourceRef",row("type","ANALYSIS_RESULT","id",r.getResultId())));
        for(var r:replies.findByIncidentIdOrderByCreatedAtAsc(incidentId)) items.add(row("recordId","reply-"+r.getReplyId(),"at",iso(r.getCreatedAt()),"type","REPLY",
                "summary",shortText(r.getFindings(),200),"authorUserId",r.getAuthorUserId(),"sourceRef",row("type","REPLY","id",r.getReplyId()),"evidenceId","E-REQ-"+r.getRequestId()));
        for(var a:actions.findByIncidentIdOrderByPerformedAtAsc(incidentId)) items.add(row("recordId","action-"+a.getActionId(),"at",iso(a.getCreatedAt()),"type","ACTION",
                "summary",shortText(a.getDescription(),200),"authorUserId",a.getPerformedBy(),"sourceRef",row("type","ACTION","id",a.getActionId()),"evidenceId","E-ACT-"+a.getActionId()));
        items.sort(Comparator.comparing((Map<String,Object> r)->rowTime(r)).reversed().thenComparing(r->String.valueOf(r.get("recordId"))));
        data.put("requests",requestItems); data.put("approvals",approvalItems); data.put("items",items);
        Map<String,Object> omitted=row("items",0,"requests",0,"approvals",0,"pendingApprovals",Math.max(0,pending.size()-1));
        data.put("omitted",omitted);
        // 최신 원인 분석과 승인 대기 참조는 유지하고 오래된 상세부터 항목 단위로 생략한다.
        while(Json.write(ApiResponse.of(data)).length()>5000) {
            List<Map<String,Object>> oldest=List.of(items,requestItems,approvalItems).stream().filter(v->!v.isEmpty())
                    .min(Comparator.comparing(v->rowTime(v.getLast()))).orElse(null);
            if(oldest==null) break;
            String key=oldest==items?"items":oldest==requestItems?"requests":"approvals";
            oldest.removeLast(); omitted.put(key,(Integer)omitted.get(key)+1);
        }
        return data;
    }

    private Map<String,Object> analysisSummary(AnalysisResult result) {
        var causes=result.getCandidateCauses();
        Map<String,Object> top=causes.isEmpty()?null:row("candidateId",causes.getFirst().get("candidateId"),
                "hypothesis",shortText(String.valueOf(causes.getFirst().get("hypothesis")),200),"causeIds",boundedCauseIds(causes.getFirst().get("causeIds")),"stance",causes.getFirst().get("stance"));
        return row("resultId",result.getResultId(),"jobId",result.getJobId(),"createdAt",iso(result.getCreatedAt()),"nextStep",result.getAnalysis().get("nextStep"),
                "summary",shortText(result.getAnswer()==null?result.getSummary():result.getAnswer(),300),"topCause",top,
                "evidenceIds",result.getEvidenceRefs().stream().map(r->r.get("evidenceId")).limit(3).toList(),
                "limitations",result.getLimitations().stream().limit(2).map(s->shortText(s,150)).toList());
    }
    private static List<String> boundedCauseIds(Object value) {
        return value instanceof List<?> list ? list.stream().limit(5).map(v->shortText(String.valueOf(v),64)).toList() : List.of();
    }
    private static Instant rowTime(Map<String,Object> row) {
        Object value=row.getOrDefault("at",row.get("createdAt"));
        return value==null?Instant.EPOCH:Instant.parse(value.toString());
    }
    private static String iso(Instant at) { return at==null?null:at.toString(); }
    private static String shortText(String text,int max) { return text==null?null:text.length()<=max?text:text.substring(0,max-1)+"…"; }
    private static Map<String,Object> row(Object...pairs) { Map<String,Object> map=new LinkedHashMap<>(); for(int n=0;n<pairs.length;n+=2)map.put((String)pairs[n],pairs[n+1]);return map; }
}
