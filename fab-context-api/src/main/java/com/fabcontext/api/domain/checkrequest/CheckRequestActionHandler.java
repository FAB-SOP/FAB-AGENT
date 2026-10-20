package com.fabcontext.api.domain.checkrequest;

import static com.fabcontext.api.domain.approval.ApprovalPayloads.parseInstant;
import static com.fabcontext.api.domain.approval.ApprovalPayloads.requireString;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.fabcontext.api.auth.UserAccount;
import com.fabcontext.api.auth.UserAccountRepository;
import com.fabcontext.api.common.ApiException;
import com.fabcontext.api.common.Ref;
import com.fabcontext.api.domain.approval.Approval;
import com.fabcontext.api.domain.approval.ApprovedActionHandler;
import com.fabcontext.api.domain.incident.Incident;

/** 확인 요청 · 정비 요청 승인 — 둘 다 확인 요청을 만들고 요청 종류(requestType)만 다르다 */
abstract class CheckRequestActionHandler implements ApprovedActionHandler {

	private final UserAccountRepository userRepository;
	private final CheckRequestService checkRequestService;
	private final String requestType;

	CheckRequestActionHandler(UserAccountRepository userRepository, CheckRequestService checkRequestService, String requestType) {
		this.userRepository = userRepository;
		this.checkRequestService = checkRequestService;
		this.requestType = requestType;
	}

	@Override
	public Map<String, Object> normalize(Map<String, Object> raw, Incident incident) {
		Map<String, Object> p = new LinkedHashMap<>();
		String ownerUserId = requireString(raw, "ownerUserId");
		UserAccount owner = userRepository.findById(ownerUserId)
			.orElseThrow(() -> ApiException.unprocessable("OWNER_NOT_FOUND", "담당자를 찾을 수 없습니다: " + ownerUserId));
		p.put("ownerUserId", ownerUserId);
		p.put("question", requireString(raw, "question"));
		Object target = raw.get("targetProcessCode");
		p.put("targetProcessCode", target != null ? target.toString()
				: owner.getProcessScopes().isEmpty() ? null : owner.getProcessScopes().getFirst());
		if (raw.get("dueAt") != null) {
			p.put("dueAt", parseInstant(raw.get("dueAt"), "dueAt").toString());
		}
		// 요청 대상 설비 · 원인 후보 · 요청 브리프도 실행 내용이다 — 승인 해시에 들어가고 확인 요청에 그대로 복사된다
		if (raw.get("equipmentId") instanceof String eq && !eq.isBlank()) {
			p.put("equipmentId", eq);
		}
		if (raw.get("causeIds") instanceof List<?> causeIds && !causeIds.isEmpty()) {
			p.put("causeIds", causeIds.stream().map(String::valueOf).toList());
		}
		if (raw.get("brief") != null) {
			p.put("brief", RequestBrief.validate(raw.get("brief")));
		}
		return p;
	}

	@Override
	public Ref execute(Approval approval, Incident incident, String actorId, String threadId) {
		CheckRequest request = checkRequestService.createFromApproval(incident, approval.getApprovalId(), approval.getPayload(),
			approval.getEvidenceIds(), actorId, threadId, requestType);
		return new Ref("CHECK_REQUEST", request.getRequestId());
	}
}
