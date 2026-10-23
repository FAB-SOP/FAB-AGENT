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
import com.fabcontext.api.domain.job.JobKind;
import com.fabcontext.api.domain.job.JobService;
import com.fabcontext.api.domain.job.JobStatus;
import com.fabcontext.api.domain.job.ProcessedAgentEvent;
import com.fabcontext.api.domain.job.ProcessedAgentEventRepository;
import com.fabcontext.api.domain.report.ReportAiSummaryService;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
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
	private final ReportAiSummaryService reportSummaries;
	private final ProcessedAgentEventRepository processedEvents;
	private final EntityManager entityManager;
	private final Clock clock;

	@Transactional
	@SuppressWarnings("unchecked")
	public Map<String, Object> handle(String jobId, Map<String, Object> event) {
		String eventId = eventId(event);
		int attempt = positiveInteger(event.get("attempt"), "attempt");
		Job job = jobService.load(jobId);
		Incident incident = incidentRepository.findById(job.getIncidentId()).orElseThrow();
		// 확정 · 추가 분석과 같은 잠금 순서(사건 → 작업). 기다리는 동안 바뀐 상태도 다시 읽는다.
		entityManager.refresh(incident, LockModeType.PESSIMISTIC_WRITE);
		entityManager.refresh(job, LockModeType.PESSIMISTIC_WRITE);
		if (processedEvents.existsById(eventId)) {
			return ignored(jobId, "DUPLICATE_EVENT");
		}
		if (attempt != job.getAttempt()) {
			return ignored(jobId, "ATTEMPT_MISMATCH");
		}
		String type = String.valueOf(event.get("type"));
		Map<String, Object> payload = (Map<String, Object>) event.getOrDefault("payload", Map.of());
		if (job.getStatus() == JobStatus.SUCCEEDED || job.getStatus() == JobStatus.FAILED) {
			return ignored(jobId, "JOB_FINISHED");
		}
		// 처리 중 예외가 나면 수신 기록과 업무 변경이 함께 롤백되어 같은 ID로 다시 처리할 수 있다.
		entityManager.persist(new ProcessedAgentEvent(eventId, jobId, attempt, type, Instant.now(clock)));
		// 선택 필드 — 작업을 끝내거나 멈추는 이벤트에 실린 이번 시도의 trace 요약(Observability)
		if (event.get("metrics") instanceof Map<?, ?> metrics) {
			job.recordMetrics((Map<String, Object>) metrics);
		}
		switch (type) {
			case "JOB_STARTED", "ANALYSIS_PROGRESS" -> {
				jobService.startExternal(job, incident);
				if (job.getKind() == JobKind.SUMMARY) {
					reportSummaries.markStarted(job);
				}
			}
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
			case "SUMMARY_READY" -> reportSummaries.applyResult(job, incident, payload);
			case "RETRY_SCHEDULED" -> scheduleRetry(job, incident, payload);
			case "JOB_FAILED" -> recordFailure(job, incident, payload);
			default -> throw InternalError.invalid("알 수 없는 이벤트 type: " + type, "");
		}
		return Map.of("jobId", jobId, "applied", true, "status", job.getStatus().name());
	}

	private static String eventId(Map<String, Object> event) {
		if (event.get("eventId") instanceof String id && !id.isBlank() && id.length() <= 255) {
			return id;
		}
		throw InternalError.invalid("eventId는 비어 있지 않은 255자 이내 문자열이어야 합니다.", "같은 이벤트를 재전송할 때는 같은 eventId를 쓰세요.");
	}

	private static int positiveInteger(Object value, String name) {
		if (value instanceof Number number && number.intValue() > 0 && number.doubleValue() == number.intValue()) {
			return number.intValue();
		}
		throw InternalError.invalid(name + "는 양의 정수여야 합니다.", "이벤트 envelope의 attempt는 현재 작업의 시도 번호입니다.");
	}

	private static Map<String, Object> ignored(String jobId, String reason) {
		return Map.of("jobId", jobId, "applied", false, "reason", reason);
	}

	private void scheduleRetry(Job job, Incident incident, Map<String, Object> payload) {
		int nextAttempt = positiveInteger(payload.get("nextAttempt"), "nextAttempt");
		if (nextAttempt != job.getAttempt() + 1) {
			throw InternalError.invalid("nextAttempt는 현재 attempt보다 정확히 1 커야 합니다.", "현재 시도에서 다음 시도로만 재시도할 수 있습니다.");
		}
		jobService.requeueExternal(job, incident, nextAttempt);
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
		if (job.getKind() == JobKind.SUMMARY) {
			// 요약 실패는 조사 이력에 남기지 않는다 — 보고서의 요약 상태와 확정자 알림으로만 보인다
			reportSummaries.fail(job, incident, String.valueOf(payload.get("errorCode")), String.valueOf(payload.get("message")));
			return;
		}
		jobService.failExternal(job, incident, String.valueOf(payload.get("errorCode")), String.valueOf(payload.get("message")));
		incident.record(Instant.now(clock), "ANALYSIS_FAILED", "agent", job.getKind() + " 작업 실패: " + payload.get("message"),
			"JOB", job.getJobId());
	}
}
