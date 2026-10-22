package com.fabcontext.api.domain.notification;

import com.fabcontext.api.common.Ref;

/**
 * 저장 전 알림 내용(수신자 공통). sourceKey는 "종류:원본 리소스 ID"로 만든다 — 같은 업무 이벤트가 다시 와도 수신자당 한 건.
 */
public record NotificationDraft(NotificationType type, String sourceId, String targetId, Ref parent,
		String incidentId, String incidentDisplayId, String actorUserId) {

	public String sourceKey() {
		return type.name() + ":" + sourceId;
	}
}
