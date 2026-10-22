package com.fabcontext.api.simulator;

import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ThreadLocalRandom;

import com.fabcontext.api.common.Ref;
import com.fabcontext.api.config.FabProperties;
import com.fabcontext.api.domain.analysis.AnalysisResult;
import com.fabcontext.api.domain.analysis.AnalysisResultRepository;
import com.fabcontext.api.domain.approval.Approval;
import com.fabcontext.api.domain.approval.ApprovalActionType;
import com.fabcontext.api.domain.approval.ApprovalService;
import com.fabcontext.api.domain.checkrequest.CheckRequest;
import com.fabcontext.api.domain.checkrequest.CheckRequestRepositories.CheckRequestRepository;
import com.fabcontext.api.domain.checkrequest.CheckRequestRepositories.ReplyRepository;
import com.fabcontext.api.domain.checkrequest.CheckRequestStatus;
import com.fabcontext.api.domain.checkrequest.Reply;
import com.fabcontext.api.domain.incident.Incident;
import com.fabcontext.api.domain.incident.IncidentRepository;
import com.fabcontext.api.domain.incident.IncidentStatus;
import com.fabcontext.api.common.stream.IncidentStreamHub;
import com.fabcontext.api.domain.job.Job;
import com.fabcontext.api.domain.job.JobKind;
import com.fabcontext.api.domain.job.JobRepository;
import com.fabcontext.api.domain.job.JobService;
import com.fabcontext.api.domain.job.JobStatus;
import com.fabcontext.api.domain.report.Report;
import com.fabcontext.api.domain.report.ReportService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 시뮬레이션 Agent 작업 처리기 — 화면 개발 · Spring 흐름 테스트용 대역(AGENT_MODE=simulator). 결과 내용은 {@link AnalysisFactory}가
 * 판정 없이 만들고, 여기서는 상태 변화(대기 · 승인 · 회신 · 재개 · 보고서)만 실제 Agent처럼 흉내 낸다.
 * 각 단계는 별도 트랜잭션으로 실행되며, 승인 · 회신 대기 시 체크포인트(waitingRef)를 남기고 반환한다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AgentWorker {

	private final JobRepository jobRepository;
	private final IncidentRepository incidentRepository;
	private final JobService jobService;
	private final AnalysisFactory analysisFactory;
	private final AnalysisResultRepository resultRepository;
	private final ApprovalService approvalService;
	private final ReportService reportService;
	private final CheckRequestRepository checkRequestRepository;
	private final ReplyRepository replyRepository;
	private final IncidentStreamHub streamHub;
	private final FabProperties properties;
	private final Clock clock;

	@Transactional
	public boolean start(String jobId) {
		Job job = jobRepository.findById(jobId).orElse(null);
		if (job == null || job.getStatus() != JobStatus.QUEUED) {
			return false;
		}
		jobService.start(job, incident(job));
		return true;
	}

	@Transactional
	public void complete(String jobId) {
		Job job = jobRepository.findById(jobId).orElse(null);
		if (job == null || job.getStatus() != JobStatus.RUNNING) {
			return;
		}
		Incident incident = incident(job);
		if (shouldFail(job, incident)) {
			fail(job, incident, "AGENT_ERROR", "시뮬레이션 Agent 분석 실패 (재실행 가능). 원본 데이터 조회에는 영향이 없습니다.");
			return;
		}
		switch (job.getKind()) {
			case BASIC -> completeBasic(job, incident);
			case DEEP -> completeDeep(job, incident);
			case RESUME -> completeResume(job, incident);
			case REPORT -> completeReport(job, incident);
		}
	}

	@Transactional
	public void fail(String jobId, String code, String message) {
		jobRepository.findById(jobId).ifPresent(job -> {
			if (!job.getStatus().isInProgress()) {
				return;
			}
			fail(job, incident(job), code, message);
		});
	}

	private void completeBasic(Job job, Incident incident) {
		AnalysisResult result = resultRepository.save(analysisFactory.basic(incident, job));
		finishWithResult(job, incident, result, "기본 분석 완료");
	}

	private void completeDeep(Job job, Incident incident) {
		AnalysisResult result = resultRepository.save(analysisFactory.deep(incident, job));
		boolean hasOpenRequest = checkRequestRepository.existsByIncidentIdAndStatus(incident.getIncidentId(),
			CheckRequestStatus.OPEN);
		Optional<Map<String, Object>> payload = hasOpenRequest ? Optional.empty()
			: analysisFactory.checkRequestPayload(incident, job);
		if (payload.isPresent()) {
			// 정해진 모양의 제안 하나(시뮬레이터) — 내부 경로로 저장 → 사람 승인(P08) 전까지 실행하지 않는다.
			@SuppressWarnings("unchecked")
			var evidenceIds = (java.util.List<String>) payload.get().remove("evidenceIds");
			Approval approval = approvalService.createAgentProposal(incident, job, ApprovalActionType.CHECK_REQUEST,
				payload.get(), "시뮬레이터 예시 제안 — 실제 분석 아님. 결과: " + result.getResultId(),
				evidenceIds, null, null);
			jobService.waitFor(job, incident, JobStatus.WAITING_APPROVAL, new Ref("APPROVAL", approval.getApprovalId()));
			streamHub.publish(incident.getIncidentId(), "analysis.result",
				Map.of("resultId", result.getResultId(), "jobId", job.getJobId(), "kind", result.getKind()));
			return;
		}
		finishWithResult(job, incident, result, "추가 분석 완료");
	}

	private void completeResume(Job job, Incident incident) {
		String trigger = String.valueOf(job.getInput().get("trigger"));
		if ("APPROVAL".equals(trigger)) {
			// 승인은 기록만 되어 있다 — 실제 Agent처럼 승인 ID로 승인된 변경 실행 경로를 부른다.
			String approvalId = String.valueOf(job.getInput().get("approvalId"));
			Approval approval = approvalService.load(approvalId);
			Ref execution = approvalService.executeApproved(approvalId, approval.getActionType(), "sim:" + job.getJobId());
			String refType = execution.type();
			String refId = execution.id();
			if ("CHECK_REQUEST".equals(refType)) {
				CheckRequest request = checkRequestRepository.findById(refId).orElse(null);
				if (request != null && request.getStatus() == CheckRequestStatus.OPEN) {
					// 회신 대기 체크포인트 저장 후 작업자 반환
					request.setWaitingJobId(job.getJobId());
					jobService.waitFor(job, incident, JobStatus.WAITING_REPLY, new Ref("CHECK_REQUEST", refId));
					return;
				}
				if (request != null) {
					// 재개 작업이 실행되기 전에 이미 회신이 도착한 경우
					String replyId = replyRepository.findByRequestIdOrderByCreatedAtAsc(refId).stream()
						.reduce((a, b) -> b).map(Reply::getReplyId).orElse(null);
					AnalysisResult result = resultRepository.save(analysisFactory.resume(incident, job, replyId));
					finishWithResult(job, incident, result, "회신 반영 조사 재개 완료");
					return;
				}
			}
			jobService.succeed(job, incident, Ref.of(refType, refId));
			return;
		}
		String replyId = (String) job.getInput().get("replyId");
		AnalysisResult result = resultRepository.save(analysisFactory.resume(incident, job, replyId));
		finishWithResult(job, incident, result, "회신 반영 조사 재개 완료");
	}

	private void completeReport(Job job, Incident incident) {
		Report report = reportService.createDraft(incident, job);
		jobService.succeed(job, incident, new Ref("REPORT", report.getReportId()));
	}

	private void finishWithResult(Job job, Incident incident, AnalysisResult result, String message) {
		jobService.succeed(job, incident, new Ref("ANALYSIS_RESULT", result.getResultId()));
		if (incident.getStatus() == IncidentStatus.OPEN) {
			incident.setStatus(IncidentStatus.INVESTIGATING);
		}
		incident.record(Instant.now(clock), "ANALYSIS_COMPLETED", "agent", message + ": " + result.getSummary(),
			"ANALYSIS_RESULT", result.getResultId());
		streamHub.publish(incident.getIncidentId(), "analysis.result",
			Map.of("resultId", result.getResultId(), "jobId", job.getJobId(), "kind", result.getKind()));
	}

	private void fail(Job job, Incident incident, String code, String message) {
		jobService.fail(job, incident, code, message);
		incident.record(Instant.now(clock), "ANALYSIS_FAILED", "agent", job.getKind() + " 작업 실패: " + message, "JOB",
			job.getJobId());
		log.info("job {} failed: {}", job.getJobId(), message);
	}

	private boolean shouldFail(Job job, Incident incident) {
		if (job.getQuestion() != null && job.getQuestion().contains("[fail]")) {
			return true;
		}
		if (job.getKind() == JobKind.BASIC && job.getAttempt() == 1
				&& Boolean.TRUE.equals(incident.getMetrics().get("simulateAgentFailure"))) {
			return true;
		}
		double rate = properties.agent().failureRate();
		return rate > 0 && ThreadLocalRandom.current().nextDouble() < rate;
	}

	private Incident incident(Job job) {
		return incidentRepository.findById(job.getIncidentId()).orElseThrow();
	}
}
