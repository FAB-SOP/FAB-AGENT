package com.fabcontext.api.domain.action;

import static com.fabcontext.api.domain.approval.ApprovalPayloads.parseInstant;
import static com.fabcontext.api.domain.approval.ApprovalPayloads.requireString;

import java.time.Clock;
import java.time.Instant;
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

/** RECORD_ACTION — 실제로 수행한 조치 기록 */
@Component
@RequiredArgsConstructor
class RecordActionHandler implements ApprovedActionHandler {

	private final ActionService actionService;
	private final Clock clock;

	@Override
	public ApprovalActionType type() {
		return ApprovalActionType.RECORD_ACTION;
	}

	@Override
	public Map<String, Object> normalize(Map<String, Object> raw, Incident incident) {
		Map<String, Object> p = new LinkedHashMap<>();
		p.put("actionType", requireString(raw, "actionType"));
		p.put("description", requireString(raw, "description"));
		Instant performedAt = parseInstant(raw.get("performedAt"), "performedAt");
		if (performedAt.isAfter(Instant.now(clock).plusSeconds(300))) {
			throw ApiException.unprocessable("PERFORMED_AT_IN_FUTURE", "실제로 수행한 조치만 기록할 수 있습니다.");
		}
		p.put("performedAt", performedAt.toString());
		if (raw.get("performedBy") != null) {
			p.put("performedBy", raw.get("performedBy").toString());
		}
		return p;
	}

	@Override
	public Ref execute(Approval approval, Incident incident, String actorId, String threadId) {
		return new Ref("ACTION", actionService.recordFromApproval(incident, approval.getApprovalId(), approval.getPayload(),
			approval.getEvidenceIds(), actorId).getActionId());
	}
}
