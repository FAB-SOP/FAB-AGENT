package com.fabcontext.api.internal.view;

import java.time.Instant;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.fabcontext.api.contract.ActionTypeNames;
import com.fabcontext.api.domain.action.IncidentAction;
import com.fabcontext.api.domain.approval.Approval;
import com.fabcontext.api.domain.checkrequest.CheckRequest;
import com.fabcontext.api.domain.checkrequest.CheckRequestRepositories.ReplyRepository;
import com.fabcontext.api.domain.checkrequest.Reply;
import com.fabcontext.api.domain.incident.Incident;
import com.fabcontext.api.domain.processdata.DataVersion;
import com.fabcontext.api.domain.processdata.ProcessEvidenceService;
import com.fabcontext.api.domain.report.Report;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * 내부 API 응답 모양 — 도구 결과 공통 형식 {data, evidenceRefs, dataVersion, warnings, synthetic}과 사건 · 요청 · 회신 ·
 * 조치 · 보고서 · 승인 View. 시각은 KST 오프셋 ISO-8601이다.
 */
@Component
@RequiredArgsConstructor
public class InternalViews {

	public static final int MAX_ITEMS = 20;

	public static final int MAX_TRACE_POINTS = 120;

	public static final ZoneId KST = ZoneId.of("Asia/Seoul");

	public static final String MAINTENANCE_TEAM = "설비정비팀";

	private final ReplyRepository replyRepository;
	private final com.fabcontext.api.domain.approval.ApprovalRepository approvals;
	private final com.fabcontext.api.domain.work.WorkItemRepository workItems;
	private final ProcessEvidenceService evidence;
	private final DataVersion dataVersion;

	public Map<String, Object> incidentView(Incident i) {
		Map<String, Object> m = new LinkedHashMap<>();
		m.put("incidentId", i.getIncidentId());
		m.put("displayId", i.getDisplayId());
		m.put("title", i.getTitle());
		m.put("anomalyType", i.getAnomalyType());
		m.put("processCode", i.getProcessCode());
		m.put("equipmentId", i.getEquipmentId());
		m.put("lotId", i.getLotId());
		m.put("waferIds", List.copyOf(i.getWaferIds()));
		m.put("runIds", List.copyOf(i.getRunIds()));
		m.put("detectedAt", iso(i.getDetectedAt()));
		m.put("status", i.getStatus().name());
		m.put("summary", i.getSummary());
		m.put("metrics", i.getMetrics());
		m.put("analysisAsOf", iso(asOf()));
		return m;
	}

	public Map<String, Object> requestView(CheckRequest r) {
		Map<String, Object> m = new LinkedHashMap<>();
		m.put("requestId", r.getRequestId());
		m.put("origin", requestOrigin(r));
		m.put("decisionAt", iso(r.getDecisionAt()));
		m.put("decisionComment", r.getDecisionComment());
		m.put("repliedAt", iso(r.getRepliedAt()));
		m.put("withdrawnAt", iso(r.getWithdrawnAt()));
		m.put("withdrawReason", r.getWithdrawReason());
		m.put("requestType", r.getRequestType());
		m.put("incidentId", r.getIncidentId());
		m.put("ownerUserId", r.getOwnerUserId());
		m.put("requesterUserId", r.getRequesterUserId());
		m.put("targetProcessCode", r.getTargetProcessCode());
		m.put("equipmentId", r.getEquipmentId());
		m.put("question", r.getQuestion());
		m.put("causeIds", List.copyOf(r.getCauseIds()));
		m.put("brief", r.getBrief());
		m.put("evidenceIds", List.copyOf(r.getEvidenceIds()));
		m.put("status", r.getStatus().name());
		m.put("version", r.getVersion());
		m.put("createdAt", iso(r.getCreatedAt()));
		List<Reply> replies = replyRepository.findByRequestIdOrderByCreatedAtAsc(r.getRequestId());
		m.put("reply", replies.isEmpty() ? null : replyView(replies.getLast()));
		return m;
	}

	public String requestOrigin(CheckRequest request) {
		if (workItems.findByRequestId(request.getRequestId()).isPresent()) return "WORK_ITEM";
		return request.getApprovalId() == null ? "UNKNOWN" : approvals.findById(request.getApprovalId())
			.map(a -> "AGENT".equals(a.getSource()) ? "AGENT_PROPOSAL" : "USER_PROPOSAL").orElse("UNKNOWN");
	}

	public Map<String, Object> replyView(Reply p) {
		Map<String, Object> m = new LinkedHashMap<>();
		m.put("replyId", p.getReplyId());
		m.put("requestId", p.getRequestId());
		m.put("authorUserId", p.getAuthorUserId());
		m.put("findings", p.getFindings());
		m.put("memo", "");
		m.put("evidenceIds", List.copyOf(p.getEvidenceIds()));
		m.put("confirmedByUser", p.isConfirmedByUser());
		m.put("createdAt", iso(p.getCreatedAt()));
		return m;
	}

	public Map<String, Object> actionView(IncidentAction a) {
		Map<String, Object> m = new LinkedHashMap<>();
		m.put("actionId", a.getActionId());
		m.put("incidentId", a.getIncidentId());
		m.put("actionType", a.getActionType());
		m.put("description", a.getDescription());
		m.put("performedAt", iso(a.getPerformedAt()));
		m.put("performedBy", a.getPerformedBy());
		m.put("verificationStatus", a.getVerificationStatus().name());
		m.put("relatedSource", a.getRelatedSource());
		m.put("createdAt", iso(a.getCreatedAt()));
		return m;
	}

	/** contracts Report 모양 */
	public Map<String, Object> reportView(Report r) {
		Map<String, Object> m = new LinkedHashMap<>();
		m.put("reportId", r.getReportId());
		m.put("incidentId", r.getIncidentId());
		m.put("version", r.getVersion());
		m.put("contentRevision", r.getContentRevision());
		m.put("status", r.getStatus().name());
		m.put("sections", r.sectionMap()); // 팀 계약 Report.sections — 다섯 키 객체(공개 API · DB는 배열)
		m.put("evidenceIds", List.copyOf(r.getEvidenceIds()));
		m.put("sourceRevision", r.getSourceRevision());
		m.put("snapshotRef", r.getSnapshotRef());
		m.put("createdAt", iso(r.getCreatedAt()));
		return m;
	}

	public Map<String, Object> approvalView(Approval a) {
		Map<String, Object> m = new LinkedHashMap<>();
		m.put("approvalId", a.getApprovalId());
		m.put("incidentId", a.getIncidentId());
		m.put("jobId", a.getJobId());
		m.put("actionType", ActionTypeNames.internal(a.getActionType())); // 내부 API는 팀 계약 이름
		m.put("payload", a.getPayload());
		m.put("rationale", a.getRationale());
		m.put("evidenceIds", List.copyOf(a.getEvidenceIds()));
		m.put("actionHash", a.getActionHash());
		m.put("status", a.getStatus().name());
		m.put("version", a.getVersion());
		m.put("expiresAt", iso(a.getExpiresAt()));
		m.put("decidedBy", a.getDecidedBy());
		m.put("comment", a.getDecisionComment());
		m.put("executionRef", a.executionRef());
		m.put("resumeJobId", a.getResumeJobId());
		m.put("sourceResultId", a.getSourceResultId());
		return m;
	}

	public Map<String, Object> env(Object data, List<Map<String, Object>> evidenceRefs, List<String> warnings) {
		Map<String, Object> m = new LinkedHashMap<>();
		m.put("data", data);
		m.put("evidenceRefs", evidenceRefs);
		m.put("dataVersion", dataVersion.current());
		m.put("warnings", warnings);
		m.put("synthetic", true);
		return m;
	}

	public Map<String, Object> ref(String evidenceId, String sourceType, String sourceId) {
		Map<String, Object> m = new LinkedHashMap<>();
		m.put("evidenceId", evidenceId);
		m.put("sourceType", sourceType);
		m.put("sourceId", sourceId);
		m.put("dataVersion", dataVersion.current());
		m.put("synthetic", true);
		return m;
	}

	/** 분석 시점(팩의 analysisAsOf) — 이 시각 이후의 자료는 돌려주지 않는다 */
	public Instant asOf() {
		return evidence.defaultAsOf();
	}

	public static String iso(Instant t) {
		return t == null ? null : t.atZone(KST).toOffsetDateTime().withNano(0).toString();
	}

	public static List<String> csv(String v) {
		if (v == null || v.isBlank()) {
			return null;
		}
		return java.util.Arrays.stream(v.split(",")).map(String::trim).filter(s -> !s.isEmpty()).toList();
	}
}
