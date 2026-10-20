package com.fabcontext.api.domain.report;

import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.fabcontext.api.auth.AuthUser;
import com.fabcontext.api.common.ApiException;
import com.fabcontext.api.common.Ids;
import com.fabcontext.api.domain.incident.Incident;
import com.fabcontext.api.domain.incident.IncidentRepository;
import com.fabcontext.api.domain.incident.IncidentService;
import com.fabcontext.api.domain.job.IncidentStreamHub;
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
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class ReportService {

	private final ReportRepository repository;
	private final IncidentService incidentService;
	private final IncidentRepository incidentRepository;
	private final JobService jobService;
	private final ReportComposer composer;
	private final IncidentStreamHub streamHub;
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
		Incident incident = incidentService.loadForUser(incidentId, user);
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
	/** 외부 Agent가 쓴 초안 저장 — 기록을 모아 쓰는 대신 Agent의 다섯 섹션을 그대로 새 DRAFT 버전으로 둔다 */
	@Transactional
	public Report createAgentDraft(Incident incident, ReportSections sections, List<String> evidenceIds, String jobId,
			String createdBy) {
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
			.sections(composition.sections())
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
		report.requireDraft();
		report.checkRevision(body.expectedContentRevision());
		report.edit(body.title(), body.sections(), user.userId(), Instant.now(clock));
		return ReportView.from(report);
	}

	@Transactional
	public ReportView confirm(String reportId, AuthUser user, ReportConfirm body) {
		Report report = loadForUser(reportId, user);
		if (!Boolean.TRUE.equals(body.reviewConfirmed())) {
			throw ApiException.unprocessable("REVIEW_CONFIRMATION_REQUIRED", "검토를 마쳤음을 reviewConfirmed=true로 확인해야 합니다.");
		}
		Incident incident = incidentRepository.findById(report.getIncidentId()).orElseThrow();
		doConfirm(report, incident, body.expectedContentRevision(), user.userId());
		return ReportView.from(report);
	}

	/** 승인된 CONFIRM_REPORT 제안 실행 */
	public Report confirmFromApproval(Incident incident, Map<String, Object> payload, String approverId) {
		String reportId = String.valueOf(payload.get("reportId"));
		Report report = repository.findById(reportId).orElseThrow(() -> ApiException.notFound("report", reportId));
		doConfirm(report, incident, ((Number) payload.get("expectedContentRevision")).longValue(), approverId);
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

	private void doConfirm(Report report, Incident incident, Long expectedRevision, String userId) {
		report.requireDraft();
		report.checkRevision(expectedRevision);
		Instant now = Instant.now(clock);
		report.confirm(userId, now);
		// 확정 시점 내용을 스냅샷으로 보존한다. 이후 export는 이 스냅샷을 내려준다.
		report.attachSnapshot(ReportComposer.markdown(report, incident));
		incident.record(now, "REPORT_CONFIRMED", userId, "보고서 v" + report.getVersion() + " 확정", "REPORT",
			report.getReportId());
		streamHub.publish(incident.getIncidentId(), "report.confirmed",
			Map.of("reportId", report.getReportId(), "version", report.getVersion()));
	}

	private Report loadForUser(String reportId, AuthUser user) {
		Report report = repository.findById(reportId).orElseThrow(() -> ApiException.notFound("report", reportId));
		incidentService.loadForUser(report.getIncidentId(), user);
		return report;
	}
}
