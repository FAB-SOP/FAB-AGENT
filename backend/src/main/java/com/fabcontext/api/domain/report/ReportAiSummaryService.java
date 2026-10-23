package com.fabcontext.api.domain.report;

import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.fabcontext.api.auth.AuthUser;
import com.fabcontext.api.common.ApiException;
import com.fabcontext.api.common.Ref;
import com.fabcontext.api.common.stream.IncidentStreamHub;
import com.fabcontext.api.domain.incident.Incident;
import com.fabcontext.api.domain.incident.IncidentService;
import com.fabcontext.api.domain.job.Job;
import com.fabcontext.api.domain.job.JobKind;
import com.fabcontext.api.domain.job.JobService;
import com.fabcontext.api.domain.notification.NotificationEvents;
import com.fabcontext.api.domain.report.ReportDtos.ReportView;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 확정본 요약(SUMMARY 작업) — 접수 · 결과 반영 · 실패 · 재요청. 결과는 그 보고서 · 그 확정본 · 현재 요약 작업일 때만
 * 저장하고, 늦게 온 예전 결과는 버리고 그 작업을 STALE_RESULT로 닫는다. 요약 실패는 조사 이력에 남기지 않는다.
 */
@Service
@RequiredArgsConstructor
public class ReportAiSummaryService {

	static final String FAILURE_NOTICE = "요약을 만들지 못했습니다. 다시 요청할 수 있습니다.";

	private final ReportRepository repository;
	private final JobService jobService;
	private final IncidentService incidentService;
	private final NotificationEvents notificationEvents;
	private final IncidentStreamHub streamHub;
	private final EntityManager entityManager;
	private final Clock clock;

	/** 확정과 같은 처리 단위에서 요약을 「대기」로 접수한다 — 등록이 실패하면 확정 · 종료도 함께 취소된다 */
	void requestFor(Report report, Incident incident, String actorId) {
		Job job = enqueueSummaryJob(report, incident, actorId);
		report.startAiSummary(job.getJobId(), Instant.now(clock));
	}

	/** 실패한 요약만 다시 요청한다 — 확정 권한(사건 운영 권한), 종료된 사건에서도 허용 */
	@Transactional
	public ReportView retry(String reportId, AuthUser user) {
		Report report = repository.findById(reportId).orElseThrow(() -> ApiException.notFound("report", reportId));
		Incident incident = incidentService.loadForWrite(report.getIncidentId(), user);
		entityManager.refresh(incident, LockModeType.PESSIMISTIC_WRITE);
		entityManager.refresh(report);
		ReportAiSummary summary = report.getAiSummary();
		if (summary == null || !summary.isRetryable()) {
			throw ApiException.conflict("SUMMARY_NOT_RETRYABLE", "실패한 요약만 다시 요청할 수 있습니다.",
				Map.of("status", summary == null ? "NONE" : summary.getStatus().name()));
		}
		Job job = enqueueSummaryJob(report, incident, user.userId());
		summary.restart(job.getJobId(), Instant.now(clock));
		publish(report);
		return ReportView.from(report);
	}

	@Transactional
	public void markStarted(Job job) {
		Report report = reportOf(job);
		if (report.getAiSummary() != null && report.getAiSummary().accepts(job.getJobId())) {
			report.getAiSummary().markRunning(job.getJobId(), Instant.now(clock));
			publish(report);
		}
	}

	/** SUMMARY_READY 반영. 저장했으면 true, 대조에 실패해 버렸으면 false */
	@Transactional
	public boolean applyResult(Job job, Incident incident, Map<String, Object> payload) {
		Report report = reportOf(job);
		if (!isForConfirmedSnapshot(report, payload)) {
			// 다른 보고서 · 다른 확정본의 결과 — 저장하지 않고 그 작업을 닫는다. 현재 요약 작업이었다면 요약도 실패로 둬 다시 요청할 수 있게 한다
			fail(job, incident, "STALE_RESULT", "현재 확정본의 결과가 아니어서 반영하지 않았습니다.");
			return false;
		}
		if (report.getAiSummary() == null || !report.getAiSummary().accepts(job.getJobId())) {
			// 현재 요약 작업이 아니다(늦게 도착한 예전 작업) — 현재 요약을 덮지 않는다
			jobService.fail(job, incident, "STALE_RESULT", "현재 요약 작업의 결과가 아니어서 반영하지 않았습니다.");
			return false;
		}
		Map<?, ?> summary = payload.get("summary") instanceof Map<?, ?> map ? map : Map.of();
		report.getAiSummary().complete(strings(summary.get("lines")), strings(summary.get("evidenceIds")),
			summary.get("generatedBy") == null ? null : String.valueOf(summary.get("generatedBy")), Instant.now(clock));
		jobService.succeed(job, incident, new Ref("REPORT", report.getReportId()));
		notificationEvents.reportSummaryFinished(true, job.getJobId(), report.getReportId(), report.getIncidentId(),
			report.getConfirmedBy());
		publish(report);
		return true;
	}

	/** 요약 작업의 최종 실패 — 짧은 안내만 남기고 조사 이력에는 기록하지 않는다 */
	@Transactional
	public void fail(Job job, Incident incident, String code, String message) {
		jobService.fail(job, incident, code, message);
		Report report = reportOf(job);
		if (report.getAiSummary() == null || !report.getAiSummary().accepts(job.getJobId())) {
			return;
		}
		report.getAiSummary().fail(FAILURE_NOTICE, Instant.now(clock));
		notificationEvents.reportSummaryFinished(false, job.getJobId(), report.getReportId(), report.getIncidentId(),
			report.getConfirmedBy());
		publish(report);
	}

	/** 요약 작업이 읽는 확정본 — 확정 전 보고서는 없다(404) */
	@Transactional(readOnly = true)
	public Map<String, Object> confirmedSnapshot(String reportId) {
		Report report = repository.findById(reportId).orElseThrow(() -> ApiException.notFound("report", reportId));
		if (!report.isConfirmed() || report.getConfirmedSnapshot() == null) {
			throw ApiException.notFound("confirmedReport", reportId);
		}
		return report.getConfirmedSnapshot();
	}

	private Job enqueueSummaryJob(Report report, Incident incident, String actorId) {
		Map<String, Object> summaryRef = new LinkedHashMap<>();
		summaryRef.put("reportId", report.getReportId());
		summaryRef.put("reportVersion", report.getVersion());
		summaryRef.put("contentRevision", report.getContentRevision());
		summaryRef.put("snapshotRef", report.getSnapshotRef());
		Map<String, Object> input = new LinkedHashMap<>();
		input.put("summaryRef", summaryRef);
		return jobService.enqueue(incident, JobKind.SUMMARY, actorId, report.getTitle(), null, input, null, null);
	}

	private static boolean isForConfirmedSnapshot(Report report, Map<String, Object> payload) {
		return report.getReportId().equals(payload.get("reportId"))
				&& report.getSnapshotRef() != null && report.getSnapshotRef().equals(payload.get("snapshotRef"));
	}

	@SuppressWarnings("unchecked")
	private Report reportOf(Job job) {
		Map<String, Object> summaryRef = (Map<String, Object>) job.getInput().get("summaryRef");
		String reportId = summaryRef == null ? null : String.valueOf(summaryRef.get("reportId"));
		return repository.findById(String.valueOf(reportId)).orElseThrow(() -> ApiException.notFound("report", reportId));
	}

	private void publish(Report report) {
		streamHub.publish(report.getIncidentId(), "report.summary",
			Map.of("reportId", report.getReportId(), "status", report.getAiSummary().getStatus().name()));
	}

	private static List<String> strings(Object value) {
		return value instanceof List<?> list ? list.stream().map(String::valueOf).toList() : List.of();
	}
}
