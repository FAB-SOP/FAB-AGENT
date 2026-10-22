package com.fabcontext.api.internal;

import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import java.util.List;

import com.fabcontext.api.common.Ref;
import com.fabcontext.api.contract.AgentResultMapper;
import com.fabcontext.api.domain.analysis.AnalysisResult;
import com.fabcontext.api.domain.analysis.AnalysisResultRepository;
import com.fabcontext.api.domain.checkrequest.CheckRequestRepositories.CheckRequestRepository;
import com.fabcontext.api.domain.incident.Incident;
import com.fabcontext.api.domain.incident.IncidentRepository;
import com.fabcontext.api.domain.incident.IncidentStatus;
import com.fabcontext.api.common.stream.IncidentStreamHub;
import com.fabcontext.api.domain.job.Job;
import com.fabcontext.api.domain.job.JobService;
import com.fabcontext.api.domain.job.JobStatus;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 외부 Agent 이벤트 수신 — contracts/schemas/agent-event.schema.json 그대로 받는다.
 * Kafka(analysis.events)를 붙이기 전까지 HTTP(POST /internal/jobs/{jobId}/events)로 같은 이벤트를 받는 임시 다리다.
 * Kafka 소비자를 붙여도 이 클래스의 handle()을 그대로 부르면 된다.
 */
@Service
@RequiredArgsConstructor
public class AgentEventService {

	private final JobService jobService;
	private final IncidentRepository incidentRepository;
	private final CheckRequestRepository checkRequestRepository;
	private final AnalysisResultRepository resultRepository;
	private final IncidentStreamHub streamHub;
	private final AgentResultMapper mapper;
	private final com.fabcontext.api.domain.work.WorkService work;
	private final Clock clock;

	@Transactional
	@SuppressWarnings("unchecked")
	public Map<String, Object> handle(String jobId, Map<String, Object> event) {
		Job job = jobService.load(jobId);
		Incident incident = incidentRepository.findById(job.getIncidentId()).orElseThrow();
		String type = String.valueOf(event.get("type"));
		Map<String, Object> payload = (Map<String, Object>) event.getOrDefault("payload", Map.of());
		if (job.getStatus() == JobStatus.SUCCEEDED || job.getStatus() == JobStatus.FAILED) {
			return Map.of("jobId", jobId, "applied", false, "reason", "이미 끝난 작업입니다: " + job.getStatus());
		}
		// 선택 필드 — 작업을 끝내거나 멈추는 이벤트에 실린 이번 시도의 trace 요약(Observability)
		if (event.get("metrics") instanceof Map<?, ?> metrics) {
			job.recordMetrics((Map<String, Object>) metrics);
		}
		switch (type) {
			case "JOB_STARTED", "ANALYSIS_PROGRESS" -> jobService.startExternal(job, incident);
			case "APPROVAL_REQUIRED" -> {
				job.putInput("interruptId", payload.get("interruptId"));
				jobService.waitFor(job, incident, JobStatus.WAITING_APPROVAL, new Ref("APPROVAL", (String) payload.get("approvalId")));
			}
			case "WAITING_REPLY" -> waitForReply(job, incident, payload);
			case "RESULT_READY" -> applyResult(job, incident, payload);
			case "REPORT_DRAFT_READY" -> {
				Map<String, Object> report = (Map<String, Object>) payload.getOrDefault("report", Map.of());
				jobService.succeed(job, incident, new Ref("REPORT", (String) report.get("reportId")));
			}
			case "RETRY_SCHEDULED" -> jobService.requeueExternal(job, incident, ((Number) payload.get("nextAttempt")).intValue());
			case "JOB_FAILED" -> recordFailure(job, incident, payload);
			default -> throw InternalError.invalid("알 수 없는 이벤트 type: " + type, "");
		}
		return Map.of("jobId", jobId, "applied", true, "status", job.getStatus().name());
	}

	private void waitForReply(Job job, Incident incident, Map<String, Object> payload) {
		String requestId = (String) payload.get("requestId");
		job.putInput("interruptId", payload.get("interruptId"));
		checkRequestRepository.findById(requestId).ifPresent(request -> request.setWaitingJobId(job.getJobId()));
		jobService.waitFor(job, incident, JobStatus.WAITING_REPLY, new Ref("CHECK_REQUEST", requestId));
	}

	/** 분석 결과 저장 → 업무 제안 반영 → 작업 완료 → 사건 조사 중 표시 · 기록 · 알림 */
	@SuppressWarnings("unchecked")
	private void applyResult(Job job, Incident incident, Map<String, Object> payload) {
		AnalysisResult result = resultRepository.save(mapper.toResult(job, incident, (Map<String, Object>) payload.get("result")));
		work.importSuggestions(incident, result,
			(List<Map<String, Object>>) payload.getOrDefault("workSuggestions", List.of()));
		jobService.succeed(job, incident, new Ref("ANALYSIS_RESULT", result.getResultId()));
		if (incident.getStatus() == IncidentStatus.OPEN) {
			incident.setStatus(IncidentStatus.INVESTIGATING);
		}
		incident.record(Instant.now(clock), "ANALYSIS_COMPLETED", "agent", "Agent 분석 완료: " + result.getSummary(),
			"ANALYSIS_RESULT", result.getResultId());
		streamHub.publish(incident.getIncidentId(), "analysis.result",
			Map.of("resultId", result.getResultId(), "jobId", job.getJobId(), "kind", result.getKind()));
	}

	private void recordFailure(Job job, Incident incident, Map<String, Object> payload) {
		jobService.failExternal(job, incident, String.valueOf(payload.get("errorCode")), String.valueOf(payload.get("message")));
		incident.record(Instant.now(clock), "ANALYSIS_FAILED", "agent", job.getKind() + " 작업 실패: " + payload.get("message"),
			"JOB", job.getJobId());
	}
}
