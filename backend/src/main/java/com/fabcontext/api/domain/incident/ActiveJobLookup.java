package com.fabcontext.api.domain.incident;

import com.fabcontext.api.domain.incident.IncidentDtos.ActiveJob;

/**
 * 사건 화면 · 스트림에 붙이는 진행 중 작업 — 작업(job) 패키지가 구현한다.
 * incident는 job을 모른다(의존 방향은 job → incident 하나).
 */
public interface ActiveJobLookup {

	/** jobId가 null이거나 작업이 없으면 null */
	ActiveJob find(String jobId);

	default java.util.List<ActiveJob> waitingForIncident(String incidentId) { return java.util.List.of(); }
}
