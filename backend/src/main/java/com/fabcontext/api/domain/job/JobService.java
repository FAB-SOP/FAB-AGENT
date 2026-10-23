package com.fabcontext.api.domain.job;

import java.time.Clock;
import java.time.Instant;
import java.util.EnumSet;
import java.util.Map;

import com.fabcontext.api.auth.AuthUser;
import com.fabcontext.api.common.ApiException;
import com.fabcontext.api.common.Ids;
import com.fabcontext.api.common.Ref;
import com.fabcontext.api.config.FabProperties;
import com.fabcontext.api.domain.incident.Incident;
import com.fabcontext.api.domain.incident.IncidentService;
import com.fabcontext.api.domain.job.JobDtos.JobEvent;
import com.fabcontext.api.domain.job.JobDtos.JobQueuedEvent;
import com.fabcontext.api.common.stream.IncidentStreamHub;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@lombok.extern.slf4j.Slf4j
@Service
@RequiredArgsConstructor
public class JobService {

	private static final EnumSet<JobStatus> IN_PROGRESS = EnumSet.of(JobStatus.QUEUED, JobStatus.RUNNING);

	private final com.fabcontext.api.domain.notification.NotificationEvents notificationEvents;
	private final JobRepository jobRepository;
	private final JobInputContract inputContract;
	private final IncidentService incidentService;
	private final IncidentStreamHub streamHub;
	private final ApplicationEventPublisher events;
	private final Clock clock;
	private final FabProperties properties;
	private final jakarta.persistence.EntityManager entityManager;

	@Transactional(readOnly = true)
	public JobDtos.JobView get(String jobId, AuthUser user) {
		Job job = jobRepository.findById(jobId).orElseThrow(() -> ApiException.notFound("job", jobId));
		incidentService.loadForUser(job.getIncidentId(), user);
		return JobDtos.JobView.from(job);
	}

	public Job load(String jobId) {
		return jobRepository.findById(jobId).orElseThrow(() -> ApiException.notFound("job", jobId));
	}

	public void assertNoRunningJob(Incident incident) {
		if (jobRepository.existsByIncidentIdAndStatusIn(incident.getIncidentId(), IN_PROGRESS)) {
			throw ApiException.conflict("JOB_IN_PROGRESS", "이미 진행 중인 분석 작업이 있습니다. getJob으로 상태를 확인하세요.",
				Map.of("activeJobId", String.valueOf(incident.getActiveJobId())));
		}
	}

	public boolean hasRunning(String incidentId, JobKind kind) {
		return jobRepository.existsByIncidentIdAndKindAndStatusIn(incidentId, kind, IN_PROGRESS);
	}

	/** 작업을 QUEUED로 저장하고 커밋 후 Agent에 투입한다. */
	public Job enqueue(Incident incident, JobKind kind, String requestedBy, String question, String scope,
			Map<String, Object> input, String threadId, String parentJobId) {
		entityManager.lock(incident, jakarta.persistence.LockModeType.PESSIMISTIC_WRITE);
		// 요약은 조사와 따로 돈다 — 진행 중인 조사 작업이 있어도 막지 않고, 사건의 조사 상태를 바꾸지 않는다
		if(kind!=JobKind.REPORT && kind.affectsInvestigationState()) assertNoRunningJob(incident);
		// 외부 Agent의 attempt는 같은 작업의 재시도 번호다. 수동 재실행으로 만든 새 작업은 다시 1부터 시작한다.
		// 시뮬레이터의 기존 화면용 BASIC 재실행 횟수는 그대로 둔다.
		int attempt = kind == JobKind.BASIC && !properties.agent().external()
			? (int) jobRepository.countByIncidentIdAndKind(incident.getIncidentId(), kind) + 1 : 1;
		Job job = Job.builder()
			.jobId(Ids.next("job"))
			.incidentId(incident.getIncidentId())
			.kind(kind)
			.attempt(attempt)
			.threadId(threadId != null ? threadId : Ids.next("thr"))
			.parentJobId(parentJobId)
			.question(question)
			.scope(scope)
			.input(inputContract.initialize(kind, input, parentJobId == null ? null : load(parentJobId)))
			.requestedBy(requestedBy)
			.createdAt(Instant.now(clock))
			.build();
		job = jobRepository.save(job);
		if (kind.affectsInvestigationState()) {
			if (kind != JobKind.REPORT) {
				incident.setActiveJobId(job.getJobId());
			}
			incident.setLatestJobStatus(job.getStatus().name());
		}
		events.publishEvent(new JobQueuedEvent(job.getJobId()));
		publish(job);
		return job;
	}

	/** 외부 Agent Worker용 — 상태 전이를 패키지 밖(internal)에서 쓴다 */
	public void startExternal(Job job, Incident incident) {
		if (job.getStatus() == JobStatus.QUEUED) {
			start(job, incident);
		}
	}

	public void failExternal(Job job, Incident incident, String code, String message) {
		fail(job, incident, code, message);
	}

	public void requeueExternal(Job job, Incident incident, int nextAttempt) {
		job.requeue(nextAttempt, Instant.now(clock));
		sync(job, incident);
	}

	public void start(Job job, Incident incident) {
		job.start(Instant.now(clock));
		sync(job, incident);
	}

	public void succeed(Job job, Incident incident, Ref result) {
		job.succeed(result, Instant.now(clock));
		if (job.getJobId().equals(incident.getActiveJobId())) {
			incident.setActiveJobId(null);
		}
		sync(job, incident);
		if (job.getKind().affectsInvestigationState()) {
			notificationEvents.analysisFinished(job, incident);
		}
	}

	public void fail(Job job, Incident incident, String code, String message) {
		job.fail(code, message, Instant.now(clock));
		sync(job, incident);
		if (job.getKind().affectsInvestigationState()) {
			notificationEvents.analysisFinished(job, incident);
		}
	}

	/**
	 * 사건이 보고서 확정으로 닫힐 때 아직 대기 중인 조사 작업을 끝낸다(기다리던 요청은 이미 회신 · 거절 · 철회됨).
	 * 분석이 끝난 것이 아니므로 분석 완료 알림은 보내지 않는다.
	 */
	public void finishWaitingOnIncidentClosed(Job job, Incident incident, Ref reportRef) {
		if (!job.getStatus().isWaiting()) {
			return;
		}
		job.succeed(reportRef, Instant.now(clock));
		if (job.getJobId().equals(incident.getActiveJobId())) {
			incident.setActiveJobId(null);
		}
		sync(job, incident);
	}

	public void waitFor(Job job, Incident incident, JobStatus waitingStatus, Ref ref) {
		job.waitFor(waitingStatus, ref, Instant.now(clock));
		sync(job, incident);
	}

	/** 대기 중이던 작업을 끝내고 같은 threadId로 RESUME 작업을 만든다. */
	public Job resume(Job waitingJob, Incident incident, String requestedBy, Map<String, Object> input) {
		if (!waitingJob.getStatus().isWaiting()) throw ApiException.conflict("JOB_NOT_WAITING","이미 처리된 대기 작업입니다.",Map.of());
		Map<String,Object> merged=new java.util.LinkedHashMap<>(input);
		for(String key:java.util.List.of("analysisRequestId","selectedInputs","followupQuestion"))
			if(!merged.containsKey(key) && waitingJob.getInput().containsKey(key)) merged.put(key,waitingJob.getInput().get(key));
		// 승인 결정자는 재개 실행의 요청자가 아니다. 직접 추가 분석을 누른 경우만 새 요청자로 삼는다.
		boolean keepRequester=waitingJob.getInput().containsKey("analysisRequestId") || "APPROVAL_DECIDED".equals(input.get("reason"));
		String requester=keepRequester?waitingJob.getRequestedBy():requestedBy;
		Job resume = enqueue(incident, JobKind.RESUME, requester, waitingJob.getQuestion(), waitingJob.getScope(),
			merged, waitingJob.getThreadId(), waitingJob.getJobId());
		waitingJob.succeed(new Ref("RESUME_JOB", resume.getJobId()), Instant.now(clock));
		publish(waitingJob);
		return resume;
	}

	private void sync(Job job, Incident incident) {
		boolean isLatest = incident.getActiveJobId() == null || job.getJobId().equals(incident.getActiveJobId());
		if (job.getKind().affectsInvestigationState() && (isLatest || job.getKind() == JobKind.REPORT)) {
			incident.setLatestJobStatus(job.getStatus().name());
		}
		publish(job);
	}

	private void publish(Job job) {
		String requestId=org.slf4j.MDC.get("requestId"), jobId=job.getJobId(), incidentId=job.getIncidentId();
		JobStatus status=job.getStatus();
		JobKind kind=job.getKind();
		com.fabcontext.api.common.AfterCommit.run(()->{
			if(status==JobStatus.FAILED) log.error("event=job.status requestId={} incidentId={} jobId={} kind={} status={}",requestId,incidentId,jobId,kind,status);
			else log.info("event=job.status requestId={} incidentId={} jobId={} kind={} status={}",requestId,incidentId,jobId,kind,status);
		});
		streamHub.publish(job.getIncidentId(), "job.status", JobEvent.from(job));
	}
}
