package com.fabcontext.api.domain.checkrequest;

import java.util.EnumSet;
import java.util.Set;

/** OPEN(받았지만 아직 수락 전) → ACCEPTED(담당자가 맡음) → REPLIED. 담당자는 수락 전 DECLINED로 거절할 수 있다. */
public enum CheckRequestStatus {
	OPEN,
	ACCEPTED,
	REPLIED,
	DECLINED,
	CANCELLED;

	/** 회신을 기다리는 상태 — 수락 전후 모두 사건은 회신 대기다 */
	public static final Set<CheckRequestStatus> AWAITING_REPLY = EnumSet.of(OPEN, ACCEPTED);
}
