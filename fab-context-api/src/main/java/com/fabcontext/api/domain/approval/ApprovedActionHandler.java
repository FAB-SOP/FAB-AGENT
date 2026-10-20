package com.fabcontext.api.domain.approval;

import java.util.Map;

import com.fabcontext.api.common.Ref;
import com.fabcontext.api.domain.incident.Incident;

/**
 * 승인 종류 하나의 입력 검사와 실행 — 실행 대상을 아는 도메인(checkrequest · action · report)에 둔다.
 * {@link ApprovalService}는 종류로 실행기를 골라 넘기기만 하므로 실행 대상 도메인을 모른다.
 */
public interface ApprovedActionHandler {

	ApprovalActionType type();

	/** 제안 payload 검사 · 정규화 — 승인 해시에 들어가고, 승인되면 이 값만 실행된다 */
	Map<String, Object> normalize(Map<String, Object> raw, Incident incident);

	/**
	 * 승인된 payload를 실행하고 만든 것을 가리킨다.
	 * @param actorId 실행 주체(승인한 사람)
	 * @param threadId 재개된 Agent가 실행할 때 그 작업의 thread(수동 승인 실행이면 null)
	 */
	Ref execute(Approval approval, Incident incident, String actorId, String threadId);
}
