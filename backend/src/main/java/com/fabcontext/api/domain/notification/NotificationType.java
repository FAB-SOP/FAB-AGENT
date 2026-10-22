package com.fabcontext.api.domain.notification;

/**
 * 사용자 알림 종류. 제목은 고정 문구만 쓴다 — 요청 질문 · 회신 내용 · 반려 사유 같은 자유 입력은 알림에 담지 않고
 * 이동 대상(target) 화면에서 그 리소스의 권한 검사를 거쳐 보게 한다.
 */
public enum NotificationType {
	/** 새 사건 → 공정·설비 담당자(미지정 시 공정 관리자) */
	INCIDENT_CREATED("INCIDENT", "새 사건이 접수되었습니다"),
	/** 새 업무·확인 요청 → 지정 수신자 */
	CHECK_REQUEST_RECEIVED("CHECK_REQUEST", "확인 요청을 받았습니다"),
	/** 요청 수락 → 요청자 (수락·거절 API가 main에 들어온 뒤 연결) */
	CHECK_REQUEST_ACCEPTED("CHECK_REQUEST", "보낸 요청이 수락되었습니다"),
	/** 요청 거절 → 요청자 (수락·거절 API가 main에 들어온 뒤 연결) */
	CHECK_REQUEST_REJECTED("CHECK_REQUEST", "보낸 요청이 거절되었습니다"),
	/** 결과 회신 → 요청자. 회신 도착 알림일 뿐 분석을 실행하지 않는다 */
	CHECK_REQUEST_REPLIED("REPLY", "보낸 요청에 회신이 도착했습니다"),
	/** 상급자 승인 요청 → 지정 승인자 */
	WORK_APPROVAL_REQUESTED("WORK_ITEM", "업무 승인 요청을 받았습니다"),
	/** 승인 → 승인 요청자 */
	WORK_APPROVED("WORK_ITEM", "요청한 업무가 승인되었습니다"),
	/** 반려 → 승인 요청자 */
	WORK_REJECTED("WORK_ITEM", "요청한 업무가 반려되었습니다"),
	/** 분석 완료 → 작업 요청자 */
	ANALYSIS_SUCCEEDED("JOB", "요청한 분석이 완료되었습니다"),
	/** 분석 실패 → 작업 요청자 */
	ANALYSIS_FAILED("JOB", "요청한 분석이 실패했습니다");

	/** 이동 대상 리소스 타입 */
	public final String targetType;

	public final String title;

	NotificationType(String targetType, String title) {
		this.targetType = targetType;
		this.title = title;
	}
}
