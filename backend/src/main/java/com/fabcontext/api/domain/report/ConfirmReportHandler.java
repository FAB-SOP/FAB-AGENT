package com.fabcontext.api.domain.report;

import static com.fabcontext.api.domain.approval.ApprovalPayloads.requireString;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.fabcontext.api.common.ApiException;
import com.fabcontext.api.common.Ref;
import com.fabcontext.api.domain.approval.Approval;
import com.fabcontext.api.domain.approval.ApprovalActionType;
import com.fabcontext.api.domain.approval.ApprovedActionHandler;
import com.fabcontext.api.domain.incident.Incident;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/** CONFIRM_REPORT — 보고서 초안 확정 */
@Component
@RequiredArgsConstructor
class ConfirmReportHandler implements ApprovedActionHandler {

	private final ReportService reportService;
	private final CauseClassificationPolicy classificationPolicy;

	@Override
	public ApprovalActionType type() {
		return ApprovalActionType.CONFIRM_REPORT;
	}

	@Override
	public Map<String, Object> normalize(Map<String, Object> raw, Incident incident) {
		String reportId = requireString(raw, "reportId");
		reportService.loadDraftOfIncident(reportId, incident.getIncidentId());
		Object revision = raw.get("expectedContentRevision");
		if (!(revision instanceof Number n)) {
			throw ApiException.unprocessable("INVALID_PAYLOAD", "payload.expectedContentRevision(숫자)이 필요합니다.");
		}
		Map<String, Object> p = new LinkedHashMap<>();
		p.put("reportId", reportId);
		p.put("expectedContentRevision", n.longValue());
		return p;
	}

	/** 승인자가 고른 원인 분류 — 없으면 승인할 수 없다(422). 정규화한 {category, equipmentParameters}만 저장된다 */
	@Override
	public Map<String, Object> normalizeDecisionInput(Map<String, Object> raw, Incident incident) {
		ReportCauseClassification classification = classificationPolicy.validate(toInput(raw),
			incident.getProcessCode(), null, null);
		Map<String, Object> normalized = new LinkedHashMap<>();
		normalized.put("category", classification.getCauseCategory().name());
		normalized.put("equipmentParameters", classification.getCauseEquipmentParameters());
		return normalized;
	}

	@Override
	public Ref execute(Approval approval, Incident incident, String actorId, String threadId) {
		// 실행 중인 이 승인과, 이 승인을 기다리던 · 재개한 작업은 확정을 막는 남은 일로 세지 않는다
		ConfirmationContext context = ConfirmationContext.fromApproval(actorId, approval.getApprovalId(),
			approval.getJobId(), approval.getResumeJobId());
		return new Ref("REPORT", reportService.confirmFromApproval(incident, approval.getPayload(), context,
			toInput(approval.getDecisionInput())).getReportId());
	}

	private static CauseClassificationInput toInput(Map<String, Object> raw) {
		if (raw == null) {
			return null;
		}
		Object category = raw.get("category");
		List<String> equipmentParameters = raw.get("equipmentParameters") instanceof List<?> list
				? list.stream().map(String::valueOf).toList()
				: List.of();
		return new CauseClassificationInput(category == null ? null : String.valueOf(category), equipmentParameters);
	}
}
