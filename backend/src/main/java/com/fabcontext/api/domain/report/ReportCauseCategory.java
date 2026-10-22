package com.fabcontext.api.domain.report;

/**
 * 보고서 원인 분류 — 4M과 보류.
 * HOLD는 4M 중 하나로 확정할 수 없을 때 고르며, 사건 상태 「보류」와는 별개다.
 */
public enum ReportCauseCategory {
	MATERIAL("Material (자재)"),
	MAN("Man (사람)"),
	METHOD("Method (방법)"),
	MACHINE("Machine (설비)"),
	HOLD("보류 (원인 미확정)");

	private final String label;

	ReportCauseCategory(String label) {
		this.label = label;
	}

	/** 화면 · Markdown · 사건 이력에 쓰는 이름 — 프론트 causeCategoryLabels와 같다 */
	public String label() {
		return label;
	}

	/** 장비 파라미터(센서)를 함께 골라야 하는 범주인가 — MACHINE만 */
	public boolean requiresEquipmentParameters() {
		return this == MACHINE;
	}
}
