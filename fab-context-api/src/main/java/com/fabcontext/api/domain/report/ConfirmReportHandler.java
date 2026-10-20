package com.fabcontext.api.domain.report;

import static com.fabcontext.api.domain.approval.ApprovalPayloads.requireString;

import java.util.LinkedHashMap;
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

	@Override
	public Ref execute(Approval approval, Incident incident, String actorId, String threadId) {
		return new Ref("REPORT", reportService.confirmFromApproval(incident, approval.getPayload(), actorId).getReportId());
	}
}
