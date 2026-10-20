package com.fabcontext.api.internal;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

import com.fabcontext.api.common.Ids;
import com.fabcontext.api.common.Ref;
import com.fabcontext.api.domain.analysis.AnalysisResult;
import com.fabcontext.api.domain.analysis.AnalysisResultRepository;
import com.fabcontext.api.domain.analysis.ResultShape;
import com.fabcontext.api.domain.checkrequest.CheckRequestRepositories.CheckRequestRepository;
import com.fabcontext.api.domain.incident.Incident;
import com.fabcontext.api.domain.incident.IncidentRepository;
import com.fabcontext.api.domain.incident.IncidentStatus;
import com.fabcontext.api.domain.job.IncidentStreamHub;
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
	private final ResultShape shape;
	private final Clock clock;

	@Transactional
	@SuppressWarnings("unchecked")
	public Map<String, Object> handle(String jobId, Map<String, Object> event) {
		Job job = jobService.load(jobId);
		Incident incident = incidentRepository.findById(job.getIncidentId()).orElseThrow();
		String type = String.valueOf(event.get("type"));
		Map<String, Object> p = (Map<String, Object>) event.getOrDefault("payload", Map.of());
		if (job.getStatus() == JobStatus.SUCCEEDED || job.getStatus() == JobStatus.FAILED) {
			return Map.of("jobId", jobId, "applied", false, "reason", "이미 끝난 작업입니다: " + job.getStatus());
		}
		switch (type) {
			case "JOB_STARTED", "ANALYSIS_PROGRESS" -> jobService.startExternal(job, incident);
			case "APPROVAL_REQUIRED" -> {
				job.putInput("interruptId", p.get("interruptId"));
				jobService.waitFor(job, incident, JobStatus.WAITING_APPROVAL, new Ref("APPROVAL", (String) p.get("approvalId")));
			}
			case "WAITING_REPLY" -> {
				String requestId = (String) p.get("requestId");
				job.putInput("interruptId", p.get("interruptId"));
				checkRequestRepository.findById(requestId).ifPresent(r -> r.setWaitingJobId(jobId));
				jobService.waitFor(job, incident, JobStatus.WAITING_REPLY, new Ref("CHECK_REQUEST", requestId));
			}
			case "RESULT_READY" -> {
				AnalysisResult result = resultRepository.save(toResult(job, incident, (Map<String, Object>) p.get("result")));
				jobService.succeed(job, incident, new Ref("ANALYSIS_RESULT", result.getResultId()));
				if (incident.getStatus() == IncidentStatus.OPEN) {
					incident.setStatus(IncidentStatus.INVESTIGATING);
				}
				incident.record(Instant.now(clock), "ANALYSIS_COMPLETED", "agent", "Agent 분석 완료: " + result.getSummary(),
					"ANALYSIS_RESULT", result.getResultId());
				streamHub.publish(incident.getIncidentId(), "analysis.result",
					Map.of("resultId", result.getResultId(), "jobId", jobId, "kind", result.getKind()));
			}
			case "REPORT_DRAFT_READY" -> {
				Map<String, Object> report = (Map<String, Object>) p.getOrDefault("report", Map.of());
				jobService.succeed(job, incident, new Ref("REPORT", (String) report.get("reportId")));
			}
			case "RETRY_SCHEDULED" -> jobService.requeueExternal(job, incident, ((Number) p.get("nextAttempt")).intValue());
			case "JOB_FAILED" -> {
				jobService.failExternal(job, incident, String.valueOf(p.get("errorCode")), String.valueOf(p.get("message")));
				incident.record(Instant.now(clock), "ANALYSIS_FAILED", "agent", job.getKind() + " 작업 실패: " + p.get("message"),
					"JOB", jobId);
			}
			default -> throw InternalError.invalid("알 수 없는 이벤트 type: " + type, "");
		}
		return Map.of("jobId", jobId, "applied", true, "status", job.getStatus().name());
	}

	/** 계약 AnalysisResult → 화면(P07)이 쓰는 결과 모양 */
	@SuppressWarnings("unchecked")
	private AnalysisResult toResult(Job job, Incident incident, Map<String, Object> r) {
		List<Map<String, Object>> facts = new ArrayList<>();
		for (Map<String, Object> f : (List<Map<String, Object>>) r.getOrDefault("observedFacts", List.of())) {
			// Agent 계약의 사실은 글(text)과 근거뿐이다 — 숫자 값 · 단위는 지어내지 않고 null로 둔다(글 안에 있다)
			facts.add(shape.fact(facts.size() + 1, (String) f.get("text"), (List<String>) f.getOrDefault("evidenceIds", List.of()),
				null, null));
		}
		List<Map<String, Object>> causes = new ArrayList<>();
		for (Map<String, Object> c : (List<Map<String, Object>>) r.getOrDefault("candidateCauses", List.of())) {
			Object next = c.get("nextCheck");
			int rank = c.get("priority") instanceof Number n ? n.intValue() : causes.size() + 1;
			causes.add(shape.cause(rank, (String) c.get("hypothesis"), incident.getProcessCode(),
				(List<String>) c.getOrDefault("supportingEvidenceIds", List.of()),
				(List<String>) c.getOrDefault("conflictingEvidenceIds", List.of()),
				next == null ? List.of() : List.of(next.toString()), (List<String>) c.getOrDefault("missingData", List.of()),
				(List<String>) c.getOrDefault("causeIds", List.of())));
		}
		List<Map<String, Object>> refs = new ArrayList<>();
		for (Map<String, Object> e : (List<Map<String, Object>>) r.getOrDefault("evidenceRefs", List.of())) {
			Map<String, Object> m = new LinkedHashMap<>();
			m.put("evidenceId", e.get("evidenceId"));
			m.put("type", e.get("sourceType"));
			m.put("label", e.get("sourceId"));
			m.put("ref", e.get("sourceId"));
			refs.add(m);
		}
		List<String> limitations = new ArrayList<>(new LinkedHashSet<>((List<String>) r.getOrDefault("limitations", List.of())));
		String top = causes.isEmpty() ? "없음" : String.valueOf(causes.getFirst().get("hypothesis"));
		String summary = "Agent " + job.getKind() + " 분석: 관측 사실 " + facts.size() + "건, 가설 " + causes.size()
				+ "건. 우선 확인 대상은 \"" + top + "\"이며 원인은 확정하지 않았습니다.";
		return AnalysisResult.builder()
			.resultId(Ids.next("res"))
			.incidentId(incident.getIncidentId())
			.jobId(job.getJobId())
			.threadId(job.getThreadId())
			.kind(job.getKind().name())
			.question(job.getQuestion())
			.summary(summary.length() > 1900 ? summary.substring(0, 1900) : summary)
			.observedFacts(facts)
			.candidateCauses(causes)
			.limitations(limitations)
			.evidenceRefs(refs)
			.nextActions(new ArrayList<>())
			.dataVersion(String.valueOf(r.getOrDefault("dataVersion", "UNKNOWN")))
			.createdAt(Instant.now(clock))
			.build();
	}
}
