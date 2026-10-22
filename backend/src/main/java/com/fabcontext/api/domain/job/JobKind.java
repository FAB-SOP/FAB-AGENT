package com.fabcontext.api.domain.job;

public enum JobKind {
	/** 사건 접수 직후 기본 분석 */
	BASIC,
	/** 사용자 추가 질문 분석 */
	DEEP,
	/** 승인·회신 이후 같은 threadId로 조사 재개 */
	RESUME,
	/** 기록 기반 보고서 초안 생성 */
	REPORT
}
