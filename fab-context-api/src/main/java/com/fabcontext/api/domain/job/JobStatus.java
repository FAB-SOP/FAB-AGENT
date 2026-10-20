package com.fabcontext.api.domain.job;

public enum JobStatus {
	QUEUED,
	RUNNING,
	/** Agent가 승인 대기 체크포인트를 저장하고 작업자를 반환한 상태 */
	WAITING_APPROVAL,
	/** 확인 요청 회신 대기 */
	WAITING_REPLY,
	SUCCEEDED,
	FAILED;

	public boolean isInProgress() {
		return this == QUEUED || this == RUNNING;
	}

	public boolean isWaiting() {
		return this == WAITING_APPROVAL || this == WAITING_REPLY;
	}
}
