package com.fabcontext.api.contract;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import com.fabcontext.api.domain.approval.ApprovalActionType;

/**
 * 승인 종류 이름 — 공개 API · DB는 enum 이름(프론트 계약 data/backend-handoff-v2/combined.openapi.json),
 * 내부 API · Agent 제안(팀 계약 tool-catalog)은 CREATE_CHECK_REQUEST · CREATE_MAINTENANCE_REQUEST. 경계에서 여기로 바꾼다.
 */
public final class ActionTypeNames {

	private static final Map<ApprovalActionType, String> INTERNAL = Map.of(
		ApprovalActionType.CHECK_REQUEST, "CREATE_CHECK_REQUEST",
		ApprovalActionType.MAINTENANCE_REQUEST, "CREATE_MAINTENANCE_REQUEST",
		ApprovalActionType.RECORD_ACTION, "RECORD_ACTION",
		ApprovalActionType.CONFIRM_REPORT, "CONFIRM_REPORT");

	/** 내부 API가 받는 actionType 목록(팀 계약 이름) — 오류 hint용 */
	public static final List<String> INTERNAL_NAMES = Arrays.stream(ApprovalActionType.values()).map(ActionTypeNames::internal).toList();

	private ActionTypeNames() {
	}

	/** 내부 API · Agent 이벤트(팀 계약)에서 쓰는 이름 */
	public static String internal(ApprovalActionType type) {
		return INTERNAL.get(type);
	}

	/** 내부 이름(CREATE_*)과 공개 이름 둘 다 받는다 */
	public static Optional<ApprovalActionType> parse(String value) {
		return Arrays.stream(ApprovalActionType.values()).filter(t -> t.name().equals(value) || internal(t).equals(value)).findFirst();
	}
}
