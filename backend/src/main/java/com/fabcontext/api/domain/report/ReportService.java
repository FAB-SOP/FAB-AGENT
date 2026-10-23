package com.fabcontext.api.domain.report;

import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.fabcontext.api.auth.AuthUser;
import com.fabcontext.api.common.ApiException;
import com.fabcontext.api.common.Ids;
import com.fabcontext.api.common.Ref;
import com.fabcontext.api.domain.incident.Incident;
import com.fabcontext.api.domain.incident.IncidentRepository;
import com.fabcontext.api.domain.incident.IncidentService;
import com.fabcontext.api.common.stream.IncidentStreamHub;
import com.fabcontext.api.domain.job.Job;
import com.fabcontext.api.domain.job.JobDtos.Accepted;
import com.fabcontext.api.domain.job.JobKind;
import com.fabcontext.api.domain.job.JobService;
import com.fabcontext.api.domain.report.ReportDtos.ReportConfirm;
import com.fabcontext.api.domain.report.ReportDtos.ReportEdit;
import com.fabcontext.api.domain.report.ReportDtos.ReportGeneration;
import com.fabcontext.api.domain.report.ReportDtos.ReportPage;
import com.fabcontext.api.domain.report.ReportDtos.ReportSummary;
import com.fabcontext.api.domain.report.ReportDtos.ReportView;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import com.fabcontext.api.contract.ReportSections;

@Service
@RequiredArgsConstructor
public class ReportService {

	private final ReportRepository repository;
	private final IncidentService incidentService;
	private final IncidentRepository incidentRepository;
	private final JobService jobService;
	private final ReportComposer composer;
	private final CauseClassificationPolicy classificationPolicy;
	private final IncidentStreamHub streamHub;
	private final ReportSnapshotFactory snapshotFactory;
	private final ReportConfirmationGuard confirmationGuard;
	private final ReportAiSummaryService aiSummaryService;
	private final EntityManager entityManager;
	private final Clock clock;

	public record Export(String filename, String markdown) {
	}

	@Transactional(readOnly = true)
	public ReportPage list(String incidentId, AuthUser user) {
		incidentService.loadForUser(incidentId, user);
		return new ReportPage(incidentId,
			repository.findByIncidentIdOrderByVersionDesc(incidentId).stream().map(ReportSummary::from).toList());
	}

	@Transactional(readOnly = true)
	public ReportView get(String reportId, AuthUser user) {
		return ReportView.from(loadForUser(reportId, user));
	}

	/** 사용자가 요청할 때만 초안을 만든다 (사건 발생 직후 자동 생성 없음). */
	@Transactional
	public Accepted generate(String incidentId, AuthUser user, ReportGeneration body) {
		Incident incident = incidentService.loadForWrite(incidentId, user);
		incident.requireNotClosed();
		incident.checkVersion(body.expectedIncidentVersion());
		if (body.baseReportVersion() != null
				&& repository.findByIncidentIdAndVersion(incidentId, body.baseReportVersion()).isEmpty()) {
			throw ApiException.unprocessable("BASE_REPORT_NOT_FOUND", "baseReportVersion " + body.baseReportVersion() + " 보고서가 없습니다.");
		}
		if (jobService.hasRunning(incidentId, JobKind.REPORT)) {
			throw ApiException.conflict("REPORT_JOB_IN_PROGRESS", "이미 보고서 초안을 생성 중입니다.", Map.of());
		}
		Map<String, Object> input = new LinkedHashMap<>();
		if (body.baseReportVersion() != null) {
			input.put("baseReportVersion", body.baseReportVersion());
		}
		Job job = jobService.enqueue(incident, JobKind.REPORT, user.userId(), null, null, input, null, null);
		incident.record(Instant.now(clock), "REPORT_REQUESTED", user.userId(), "보고서 초안 생성 요청", "JOB",
			job.getJobId());
		return Accepted.of(job, incident.getDisplayId(), false);
	}

	/** REPORT 작업이 호출: 기록을 모아 새 version의 DRAFT를 만든다. */
	/** 외부 Agent 초안의 다섯 섹션에 DB의 담당자·처리 이력을 덧붙여 새 DRAFT로 저장한다 */
	@Transactional
	public Report createAgentDraft(Incident incident, ReportSections sections, List<String> evidenceIds, String jobId,
			String createdBy) {
		// 확정으로 종료된 사건에는 늦게 도착한 초안을 새 판본으로 저장하지 않는다
		incident.requireNotClosed();
		sections = new ReportSections(sections.overview()+"\n- 보고서 작성 요청자: "+composer.person(jobId==null?createdBy:jobService.load(jobId).getRequestedBy()),sections.investigation(),
			sections.requestsAndReplies()+"\n\n"+composer.audit(incident),sections.actionsAndVerification(),sections.unresolved());
		int nextVersion = repository.findFirstByIncidentIdOrderByVersionDesc(incident.getIncidentId())
			.map(r -> r.getVersion() + 1).orElse(1);
		Instant now = Instant.now(clock);
		Report report = repository.save(Report.builder()
			.reportId(Ids.next("rpt"))
			.incidentId(incident.getIncidentId())
			.processCode(incident.getProcessCode())
			.version(nextVersion)
			.title(incident.getDisplayId() + " " + incident.getTitle() + " 조사 보고서")
			.sourceRevision(incident.getVersion())
			.sections(sections)
			.evidenceIds(evidenceIds)
			.generatedByJobId(jobId)
			.createdBy(createdBy)
			.createdAt(now)
			.build());
		incident.record(now, "REPORT_DRAFTED", "agent", "보고서 v" + nextVersion + " 초안 생성 (Agent)", "REPORT",
			report.getReportId());
		streamHub.publish(incident.getIncidentId(), "report.ready",
			Map.of("reportId", report.getReportId(), "version", nextVersion));
		return report;
	}

	public Report createDraft(Incident incident, Job job) {
		incident.requireNotClosed();
		Integer baseVersion = job.getInput().get("baseReportVersion") == null ? null
			: ((Number) job.getInput().get("baseReportVersion")).intValue();
		Report base = baseVersion == null ? null
			: repository.findByIncidentIdAndVersion(incident.getIncidentId(), baseVersion).orElse(null);
		int nextVersion = repository.findFirstByIncidentIdOrderByVersionDesc(incident.getIncidentId())
			.map(r -> r.getVersion() + 1).orElse(1);
		ReportComposer.Composition composition = composer.compose(incident, base);
		Instant now = Instant.now(clock);
		Report report = Report.builder()
			.reportId(Ids.next("rpt"))
			.incidentId(incident.getIncidentId())
			.processCode(incident.getProcessCode())
			.version(nextVersion)
			.title(incident.getDisplayId() + " " + incident.getTitle() + " 조사 보고서")
			.sourceRevision(incident.getVersion())
			.sections(new ReportSections(composition.sections().overview()+"\n- 보고서 작성 요청자: "+composer.person(job.getRequestedBy()),composition.sections().investigation(),composition.sections().requestsAndReplies(),composition.sections().actionsAndVerification(),composition.sections().unresolved()))
			.evidenceIds(composition.evidenceIds())
			.baseReportVersion(baseVersion)
			.generatedByJobId(job.getJobId())
			.createdBy(job.getRequestedBy())
			.createdAt(now)
			.build();
		report = repository.save(report);
		incident.record(now, "REPORT_DRAFTED", "agent", "보고서 v" + nextVersion + " 초안 생성", "REPORT",
			report.getReportId());
		streamHub.publish(incident.getIncidentId(), "report.ready",
			Map.of("reportId", report.getReportId(), "version", nextVersion));
		return report;
	}

	@Transactional
	public ReportView edit(String reportId, AuthUser user, ReportEdit body) {
		Report report = loadForUser(reportId, user);
		Incident incident = incidentService.loadForWrite(report.getIncidentId(), user);
		report.requireDraft();
		incident.requireNotClosed();
		report.checkRevision(body.expectedContentRevision());
		report.edit(body.title(), body.sections(), user.userId(), Instant.now(clock));
		return ReportView.from(report);
	}

	@Transactional
	public ReportView confirm(String reportId, AuthUser user, ReportConfirm body) {
		Report report = loadForUser(reportId, user);
		Incident incident = incidentService.loadForWrite(report.getIncidentId(), user);
		if (!Boolean.TRUE.equals(body.reviewConfirmed())) {
			throw ApiException.unprocessable("REVIEW_CONFIRMATION_REQUIRED", "검토를 마쳤음을 reviewConfirmed=true로 확인해야 합니다.");
		}
		doConfirm(report, incident, body.expectedContentRevision(), ConfirmationContext.direct(user.userId()),
			body.classification());
		return ReportView.from(report);
	}

	/** 승인된 CONFIRM_REPORT 제안 실행 — 분류는 승인자가 승인할 때 고른 값(승인 기록에 저장된 값)이다 */
	public Report confirmFromApproval(Incident incident, Map<String, Object> payload, ConfirmationContext context,
			CauseClassificationInput classification) {
		String reportId = String.valueOf(payload.get("reportId"));
		Report report = repository.findById(reportId).orElseThrow(() -> ApiException.notFound("report", reportId));
		doConfirm(report, incident, ((Number) payload.get("expectedContentRevision")).longValue(), context,
			classification);
		return report;
	}

	public Report loadDraftOfIncident(String reportId, String incidentId) {
		Report report = repository.findById(reportId).orElseThrow(() -> ApiException.notFound("report", reportId));
		if (!report.getIncidentId().equals(incidentId)) {
			throw ApiException.unprocessable("REPORT_INCIDENT_MISMATCH", "다른 사건의 보고서입니다.");
		}
		report.requireDraft();
		return report;
	}

	@Transactional(readOnly = true)
	public Export export(String reportId, AuthUser user, String format) {
		if (format != null && !format.equalsIgnoreCase("markdown") && !format.equalsIgnoreCase("md")) {
			throw ApiException.unprocessable("UNSUPPORTED_FORMAT", "지원 형식은 markdown입니다.");
		}
		Report report = loadForUser(reportId, user);
		Incident incident = incidentRepository.findById(report.getIncidentId()).orElseThrow();
		String markdown = report.getStatus() == ReportStatus.CONFIRMED && report.getSnapshotMarkdown() != null
				? report.getSnapshotMarkdown()
				: "<!-- DRAFT: 확정되지 않은 초안입니다 -->\n" + ReportComposer.markdown(report, incident);
		return new Export(incident.getDisplayId() + "-report-v" + report.getVersion() + ".md", markdown);
	}

	/**
	 * 확정 · 확정본 · 사건 종료 · 요약 접수를 한 처리 단위로 한다. 사건 행을 잠근 뒤 최신 상태로 다시 읽어, 동시에 온 확정 요청은
	 * 하나만 통과한다. 검사 순서: 초안 여부 → 판본 → 사건당 확정 1회 · 최신 판본 → 사건 종료 여부 → 남은 일 → 원인 분류
	 * (기존 실패 응답을 그대로 두려고 새 검사는 판본 다음, 분류는 맨 뒤).
	 */
	private void doConfirm(Report report, Incident incident, Long expectedRevision, ConfirmationContext context,
			CauseClassificationInput classificationInput) {
		lockForConfirmation(report, incident);
		report.requireDraft();
		report.checkRevision(expectedRevision);
		requireFirstConfirmationOfLatest(report);
		incident.requireNotClosed();
		confirmationGuard.requireNone(incident, context);
		Instant now = Instant.now(clock);
		ReportCauseClassification classification = classificationPolicy.validate(classificationInput,
			incident.getProcessCode(), context.actorId(), now);
		confirmAndFreeze(report, incident, context.actorId(), classification, now);
		closeIncident(report, incident, context.actorId(), now);
		aiSummaryService.requestFor(report, incident, context.actorId());
		streamHub.publish(incident.getIncidentId(), "report.confirmed",
			Map.of("reportId", report.getReportId(), "version", report.getVersion()));
		streamHub.publish(incident.getIncidentId(), "incident.updated",
			Map.of("status", incident.getStatus().name(), "version", incident.getVersion()));
	}

	/** 같은 사건의 확정 · 작업 등록을 직렬화한다 — 잠근 뒤 보고서 · 사건을 최신 상태로 다시 읽는다 */
	private void lockForConfirmation(Report report, Incident incident) {
		entityManager.flush();
		entityManager.refresh(incident, LockModeType.PESSIMISTIC_WRITE);
		entityManager.refresh(report);
	}

	private void requireFirstConfirmationOfLatest(Report report) {
		repository.findFirstByIncidentIdAndStatus(report.getIncidentId(), ReportStatus.CONFIRMED).ifPresent(confirmed -> {
			throw ApiException.conflict("REPORT_ALREADY_CONFIRMED", "이 사건은 이미 보고서 v" + confirmed.getVersion()
					+ "로 확정되었습니다. 확정은 사건당 한 번입니다.",
				Map.of("reportId", confirmed.getReportId(), "version", confirmed.getVersion()));
		});
		Report latest = repository.findFirstByIncidentIdOrderByVersionDesc(report.getIncidentId()).orElseThrow();
		if (!latest.getReportId().equals(report.getReportId())) {
			throw ApiException.conflict("NOT_LATEST_DRAFT", "가장 최신 판본(v" + latest.getVersion() + ")만 확정할 수 있습니다.",
				Map.of("latestReportId", latest.getReportId(), "latestVersion", latest.getVersion()));
		}
	}

	private void closeIncident(Report report, Incident incident, String actorId, Instant now) {
		Ref reportRef = new Ref("REPORT", report.getReportId());
		confirmationGuard.waitingJobsToFinish(incident)
			.forEach(job -> jobService.finishWaitingOnIncidentClosed(job, incident, reportRef));
		incident.closeByConfirmedReport(now, actorId, report.getReportId(), report.getVersion());
	}

	private void confirmAndFreeze(Report report, Incident incident, String actorId,
			ReportCauseClassification classification, Instant now) {
		report.confirm(actorId, now, classification);
		// 확정 시점 내용을 보존한다 — 내려받기는 Markdown 스냅샷, Agent 요약은 칸 이름별 확정본을 읽는다
		report.attachSnapshot(ReportComposer.markdown(report, incident));
		report.attachConfirmedSnapshot(snapshotFactory.create(report, incident));
		incident.record(now, "REPORT_CONFIRMED", actorId,
			"보고서 v" + report.getVersion() + " 확정 · 원인 분류: " + ReportComposer.describe(classification), "REPORT",
			report.getReportId());
	}

	private Report loadForUser(String reportId, AuthUser user) {
		Report report = repository.findById(reportId).orElseThrow(() -> ApiException.notFound("report", reportId));
		incidentService.loadForUser(report.getIncidentId(), user);
		return report;
	}
}
