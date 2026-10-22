package com.fabcontext.api.domain.work;

/** MVP 업무 정책. 실제 설비 실행 명령이 아니라 확인·계획·협업의 처리 경로다. */
public enum WorkOperation {
	REVIEW_SENSOR_DATA("센서 실측·설정값 비교", Route.SELF_CHECK),
	REVIEW_PROCESS_HISTORY("재료·정비·운전 이력 확인", Route.SELF_CHECK),
	COMPARE_METROLOGY("계측 결과 비교", Route.SELF_CHECK),
	CHANGE_SETPOINT("설정값 변경 계획", Route.MANAGER_APPROVAL),
	CHANGE_RECIPE("레시피 변경 계획", Route.MANAGER_APPROVAL),
	REPLACE_COMPONENT("부품 교체 계획", Route.MANAGER_APPROVAL),
	RESTART_EQUIPMENT("설비 재가동 계획", Route.MANAGER_APPROVAL),
	REVIEW_REQUIRED("업무 범위·변경 여부 검토 필요", Route.MANAGER_APPROVAL),
	EQUIPMENT_INSPECTION("장비팀 현장 점검 요청", Route.TEAM_REQUEST),
	FACILITY_INSPECTION("시설·공급 계통 점검 요청", Route.TEAM_REQUEST),
	UPSTREAM_CHECK("앞 공정 담당자 확인 요청", Route.TEAM_REQUEST);

	public enum Route { SELF_CHECK, MANAGER_APPROVAL, TEAM_REQUEST }
	public final String label;
	public final Route route;
	WorkOperation(String label, Route route) { this.label = label; this.route = route; }
}
