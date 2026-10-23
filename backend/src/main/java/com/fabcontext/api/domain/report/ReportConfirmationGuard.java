package com.fabcontext.api.domain.report;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import com.fabcontext.api.common.ApiException;
import com.fabcontext.api.domain.approval.Approval;
import com.fabcontext.api.domain.approval.ApprovalRepository;
import com.fabcontext.api.domain.approval.ApprovalStatus;
import com.fabcontext.api.domain.checkrequest.CheckRequest;
import com.fabcontext.api.domain.checkrequest.CheckRequestRepositories.CheckRequestRepository;
import com.fabcontext.api.domain.checkrequest.CheckRequestStatus;
import com.fabcontext.api.domain.incident.Incident;
import com.fabcontext.api.domain.job.Job;
import com.fabcontext.api.domain.job.JobKind;
import com.fabcontext.api.domain.job.JobRepository;
import com.fabcontext.api.domain.job.JobStatus;
import com.fabcontext.api.domain.report.ConfirmationBlocker.Kind;
import com.fabcontext.api.domain.work.WorkItem;
import com.fabcontext.api.domain.work.WorkItemRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * 보고서 확정 전 남은 일 검사. 다른 도메인의 기록을 읽기만 하고, 요청 · 업무를 자동으로 정리하지 않는다(정리는 사람이 한다).
 * 회신 대기 작업은 기다리는 확인 요청이 아직 회신되지 않았을 때만 막는다 — 회신 · 거절 · 철회로 끝난 요청을 기다리는
 * 작업은 확정을 막지 않고, 확정 때 「사건 종료로 끝남」으로 닫힌다.
 */
@Component
@RequiredArgsConstructor
class ReportConfirmationGuard {

	private static final Set<JobStatus> ANALYSIS_ACTIVE = EnumSet.of(JobStatus.QUEUED, JobStatus.RUNNING,
		JobStatus.WAITING_APPROVAL);
	private static final Set<JobStatus> REPORT_ACTIVE = EnumSet.of(JobStatus.QUEUED, JobStatus.RUNNING);
	private static final Set<CheckRequestStatus> REQUEST_UNANSWERED = EnumSet.of(CheckRequestStatus.OPEN,
		CheckRequestStatus.ACCEPTED);
	private static final Set<WorkItem.Status> WORK_ADOPTED_NOT_DONE = EnumSet.of(WorkItem.Status.READY,
		WorkItem.Status.APPROVED, WorkItem.Status.REQUESTED);

	private final JobRepository jobRepository;
	private final ApprovalRepository approvalRepository;
	private final CheckRequestRepository checkRequestRepository;
	private final WorkItemRepository workItemRepository;

	void requireNone(Incident incident, ConfirmationContext context) {
		List<ConfirmationBlocker> blockers = findBlockers(incident, context);
		if (!blockers.isEmpty()) {
			throw ApiException.conflict("CONFIRMATION_BLOCKED", "확정 전에 정리할 일이 " + blockers.size() + "건 남아 있습니다.",
				Map.of("blockers", blockers));
		}
	}

	List<ConfirmationBlocker> findBlockers(Incident incident, ConfirmationContext context) {
		String incidentId = incident.getIncidentId();
		Set<String> unansweredRequestIds = checkRequestRepository.findByIncidentIdOrderByCreatedAtAsc(incidentId).stream()
			.filter(request -> REQUEST_UNANSWERED.contains(request.getStatus()))
			.map(CheckRequest::getRequestId)
			.collect(Collectors.toSet());
		List<ConfirmationBlocker> blockers = new ArrayList<>();
		blockers.addAll(analysisInProgress(incidentId, context, unansweredRequestIds));
		blockers.addAll(reportDraftsInProgress(incidentId));
		blockers.addAll(pendingApprovals(incidentId, context));
		blockers.addAll(workBlockers(incidentId));
		blockers.addAll(unansweredRequests(incidentId, unansweredRequestIds));
		return blockers;
	}

	/** 확정 때 닫을 회신 대기 작업 — 기다리던 요청이 이미 끝났다 */
	List<Job> waitingJobsToFinish(Incident incident) {
		return jobRepository.findByIncidentIdOrderByCreatedAtDesc(incident.getIncidentId()).stream()
			.filter(job -> job.getKind().affectsInvestigationState() && job.getStatus().isWaiting())
			.toList();
	}

	private List<ConfirmationBlocker> analysisInProgress(String incidentId, ConfirmationContext context,
			Set<String> unansweredRequestIds) {
		return jobRepository.findByIncidentIdOrderByCreatedAtDesc(incidentId).stream()
			.filter(job -> isInvestigation(job.getKind()) && !context.excludesJob(job.getJobId()))
			.filter(job -> ANALYSIS_ACTIVE.contains(job.getStatus())
					|| (job.getStatus() == JobStatus.WAITING_REPLY && unansweredRequestIds.contains(job.getWaitingRefId())))
			.map(job -> new ConfirmationBlocker(Kind.ANALYSIS_IN_PROGRESS, job.getJobId(),
				"조사 분석 " + job.getKind() + " · " + job.getStatus()))
			.toList();
	}

	private List<ConfirmationBlocker> reportDraftsInProgress(String incidentId) {
		return jobRepository.findByIncidentIdOrderByCreatedAtDesc(incidentId).stream()
			.filter(job -> job.getKind() == JobKind.REPORT && REPORT_ACTIVE.contains(job.getStatus()))
			.map(job -> new ConfirmationBlocker(Kind.REPORT_IN_PROGRESS, job.getJobId(), "보고서 초안 작업 · " + job.getStatus()))
			.toList();
	}

	private List<ConfirmationBlocker> pendingApprovals(String incidentId, ConfirmationContext context) {
		return approvalRepository.findByIncidentIdAndStatus(incidentId, ApprovalStatus.PENDING).stream()
			.filter(approval -> !context.excludesApproval(approval.getApprovalId()))
			.map(ReportConfirmationGuard::approvalBlocker)
			.toList();
	}

	private List<ConfirmationBlocker> workBlockers(String incidentId) {
		List<ConfirmationBlocker> blockers = new ArrayList<>();
		for (WorkItem work : workItemRepository.findByIncidentIdOrderByCreatedAtAsc(incidentId)) {
			if (work.getStatus() == WorkItem.Status.WAITING_MANAGER) {
				blockers.add(new ConfirmationBlocker(Kind.MANAGER_APPROVAL_PENDING, work.getTaskId(), work.getTitle()));
			}
			else if (WORK_ADOPTED_NOT_DONE.contains(work.getStatus())) {
				blockers.add(new ConfirmationBlocker(Kind.WORK_UNPERFORMED, work.getTaskId(), work.getTitle()));
			}
		}
		return blockers;
	}

	private List<ConfirmationBlocker> unansweredRequests(String incidentId, Set<String> unansweredRequestIds) {
		return checkRequestRepository.findByIncidentIdOrderByCreatedAtAsc(incidentId).stream()
			.filter(request -> unansweredRequestIds.contains(request.getRequestId()))
			.map(request -> new ConfirmationBlocker(Kind.REQUEST_UNANSWERED, request.getRequestId(),
				"확인 요청(" + request.getStatus() + "): " + request.getQuestion()))
			.toList();
	}

	private static ConfirmationBlocker approvalBlocker(Approval approval) {
		return new ConfirmationBlocker(Kind.APPROVAL_PENDING, approval.getApprovalId(),
			"승인 대기 제안: " + approval.getActionType());
	}

	/** 보고서 초안 · 요약 작업은 조사 분석이 아니다 */
	private static boolean isInvestigation(JobKind kind) {
		return kind != JobKind.REPORT && kind.affectsInvestigationState();
	}
}
