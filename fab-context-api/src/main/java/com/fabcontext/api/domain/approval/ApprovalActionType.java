package com.fabcontext.api.domain.approval;

import java.util.Arrays;
import java.util.Optional;

/**
 * 승인 대상 변경의 종류. 이 이름이 DB 저장값이자 공개 API(프론트 계약 backend-handoff-v2/combined.openapi.json) 값이다.
 * 내부 API · Agent 제안(팀 계약 tool-catalog)은 {@link #internalName()}(CREATE_CHECK_REQUEST · CREATE_MAINTENANCE_REQUEST)을 쓰고,
 * Spring이 경계에서 바꾼다.
 */
public enum ApprovalActionType {
	/** 다른 공정 담당자에게 확인 요청 발송 */
	CHECK_REQUEST("CREATE_CHECK_REQUEST"),
	/** 같은 공정 설비정비팀에 점검 요청 (팩 계약 create_maintenance_request) — 장비 설정은 바꾸지 않는다. 프론트 계약에 새로 더한 값 */
	MAINTENANCE_REQUEST("CREATE_MAINTENANCE_REQUEST"),
	/** 실제 수행한 조치 기록 (AI 대리 기록) */
	RECORD_ACTION("RECORD_ACTION"),
	/** 보고서 확정 */
	CONFIRM_REPORT("CONFIRM_REPORT");

	private final String internalName;

	ApprovalActionType(String internalName) {
		this.internalName = internalName;
	}

	/** 내부 API · Agent 이벤트(팀 계약)에서 쓰는 이름 */
	public String internalName() {
		return internalName;
	}

	/** 내부 이름(CREATE_*)과 공개 이름 둘 다 받는다 */
	public static Optional<ApprovalActionType> parse(String value) {
		return Arrays.stream(values()).filter(t -> t.name().equals(value) || t.internalName.equals(value)).findFirst();
	}
}
