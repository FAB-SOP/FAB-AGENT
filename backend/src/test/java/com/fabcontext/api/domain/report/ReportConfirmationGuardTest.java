package com.fabcontext.api.domain.report;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;

import com.fabcontext.api.common.ApiException;
import com.fabcontext.api.domain.approval.Approval;
import com.fabcontext.api.domain.approval.ApprovalActionType;
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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ReportConfirmationGuardTest {

	static final String INCIDENT = "inc_1";

	JobRepository jobs = mock(JobRepository.class);
	ApprovalRepository approvals = mock(ApprovalRepository.class);
	CheckRequestRepository requests = mock(CheckRequestRepository.class);
	WorkItemRepository works = mock(WorkItemRepository.class);
	ReportConfirmationGuard guard = new ReportConfirmationGuard(jobs, approvals, requests, works);
	Incident incident = mock(Incident.class);

	@BeforeEach
	void emptyIncident() {
		when(incident.getIncidentId()).thenReturn(INCIDENT);
		when(jobs.findByIncidentIdOrderByCreatedAtDesc(INCIDENT)).thenReturn(List.of());
		when(approvals.findByIncidentIdAndStatus(INCIDENT, ApprovalStatus.PENDING)).thenReturn(List.of());
		when(requests.findByIncidentIdOrderByCreatedAtAsc(INCIDENT)).thenReturn(List.of());
		when(works.findByIncidentIdOrderByCreatedAtAsc(INCIDENT)).thenReturn(List.of());
	}

	static Job job(String id, JobKind kind, JobStatus status, String waitingRequestId) {
		Job job = mock(Job.class);
		when(job.getJobId()).thenReturn(id);
		when(job.getKind()).thenReturn(kind);
		when(job.getStatus()).thenReturn(status);
		when(job.getWaitingRefId()).thenReturn(waitingRequestId);
		return job;
	}

	static CheckRequest request(String id, CheckRequestStatus status) {
		CheckRequest request = mock(CheckRequest.class);
		when(request.getRequestId()).thenReturn(id);
		when(request.getStatus()).thenReturn(status);
		when(request.getQuestion()).thenReturn("두께 확인 부탁드립니다");
		return request;
	}

	static WorkItem work(String id, WorkItem.Status status) {
		WorkItem work = mock(WorkItem.class);
		when(work.getTaskId()).thenReturn(id);
		when(work.getStatus()).thenReturn(status);
		when(work.getTitle()).thenReturn("업무 " + id);
		return work;
	}

	static Approval approval(String id) {
		Approval approval = mock(Approval.class);
		when(approval.getApprovalId()).thenReturn(id);
		when(approval.getActionType()).thenReturn(ApprovalActionType.CHECK_REQUEST);
		return approval;
	}

	List<Kind> kinds(ConfirmationContext context) {
		return guard.findBlockers(incident, context).stream().map(ConfirmationBlocker::kind).toList();
	}

	@Test
	void nothingLeftMeansNoBlockers() {
		assertThat(guard.findBlockers(incident, ConfirmationContext.direct("u1"))).isEmpty();
	}

	@Test
	void activeAnalysisBlocksButReportAndSummaryJobsDoNot() {
		var incidentJobs = List.of(
			job("j1", JobKind.BASIC, JobStatus.QUEUED, null), job("j2", JobKind.DEEP, JobStatus.RUNNING, null),
			job("j3", JobKind.RESUME, JobStatus.WAITING_APPROVAL, null), job("j4", JobKind.REPORT, JobStatus.RUNNING, null),
			job("j5", JobKind.SUMMARY, JobStatus.QUEUED, null), job("j6", JobKind.BASIC, JobStatus.SUCCEEDED, null),
			job("j7", JobKind.BASIC, JobStatus.FAILED, null));
		when(jobs.findByIncidentIdOrderByCreatedAtDesc(INCIDENT)).thenReturn(incidentJobs);
		assertThat(guard.findBlockers(incident, ConfirmationContext.direct("u1")))
			.extracting(ConfirmationBlocker::id).containsExactly("j1", "j2", "j3");
	}

	@Test
	void replyWaitingJobBlocksOnlyWhileItsRequestIsUnanswered() {
		var incidentJobs = List.of(
			job("waits-open", JobKind.BASIC, JobStatus.WAITING_REPLY, "req-open"),
			job("waits-withdrawn", JobKind.DEEP, JobStatus.WAITING_REPLY, "req-withdrawn"));
		when(jobs.findByIncidentIdOrderByCreatedAtDesc(INCIDENT)).thenReturn(incidentJobs);
		var incidentRequests = List.of(
			request("req-open", CheckRequestStatus.OPEN), request("req-withdrawn", CheckRequestStatus.CANCELLED));
		when(requests.findByIncidentIdOrderByCreatedAtAsc(INCIDENT)).thenReturn(incidentRequests);
		assertThat(guard.findBlockers(incident, ConfirmationContext.direct("u1")))
			.extracting(ConfirmationBlocker::id).containsExactly("waits-open", "req-open");
	}

	@Test
	void unansweredRequestsBlockAndFinishedOnesDoNot() {
		var incidentRequests = List.of(
			request("r1", CheckRequestStatus.OPEN), request("r2", CheckRequestStatus.ACCEPTED),
			request("r3", CheckRequestStatus.REJECTED), request("r4", CheckRequestStatus.REPLIED),
			request("r5", CheckRequestStatus.CANCELLED));
		when(requests.findByIncidentIdOrderByCreatedAtAsc(INCIDENT)).thenReturn(incidentRequests);
		assertThat(guard.findBlockers(incident, ConfirmationContext.direct("u1")))
			.extracting(ConfirmationBlocker::id).containsExactly("r1", "r2");
		assertThat(kinds(ConfirmationContext.direct("u1"))).containsOnly(Kind.REQUEST_UNANSWERED);
	}

	@Test
	void adoptedButUnperformedWorkAndManagerApprovalBlock() {
		var incidentWorks = List.of(
			work("w1", WorkItem.Status.READY), work("w2", WorkItem.Status.APPROVED), work("w3", WorkItem.Status.REQUESTED),
			work("w4", WorkItem.Status.WAITING_MANAGER), work("w5", WorkItem.Status.PROPOSED),
			work("w6", WorkItem.Status.DECLINED), work("w7", WorkItem.Status.REJECTED), work("w8", WorkItem.Status.COMPLETED),
			work("w9", WorkItem.Status.CLOSED_UNPERFORMED));
		when(works.findByIncidentIdOrderByCreatedAtAsc(INCIDENT)).thenReturn(incidentWorks);
		assertThat(guard.findBlockers(incident, ConfirmationContext.direct("u1")))
			.extracting(ConfirmationBlocker::id, ConfirmationBlocker::kind)
			.containsExactly(org.assertj.core.groups.Tuple.tuple("w1", Kind.WORK_UNPERFORMED),
				org.assertj.core.groups.Tuple.tuple("w2", Kind.WORK_UNPERFORMED),
				org.assertj.core.groups.Tuple.tuple("w3", Kind.WORK_UNPERFORMED),
				org.assertj.core.groups.Tuple.tuple("w4", Kind.MANAGER_APPROVAL_PENDING));
	}

	@Test
	void approvalPathExcludesItsOwnApprovalAndJobs() {
		var pendingApprovals = List.of(approval("apr-self"), approval("apr-other"));
		when(approvals.findByIncidentIdAndStatus(INCIDENT, ApprovalStatus.PENDING)).thenReturn(pendingApprovals);
		var incidentJobs = List.of(
			job("resume-self", JobKind.RESUME, JobStatus.RUNNING, null), job("other", JobKind.DEEP, JobStatus.RUNNING, null));
		when(jobs.findByIncidentIdOrderByCreatedAtDesc(INCIDENT)).thenReturn(incidentJobs);
		ConfirmationContext context = ConfirmationContext.fromApproval("u1", "apr-self", "waiting-self", "resume-self");
		assertThat(guard.findBlockers(incident, context)).extracting(ConfirmationBlocker::id)
			.containsExactly("other", "apr-other");
	}

	@Test
	void requireNoneListsEveryBlocker() {
		var incidentRequests = List.of(request("r1", CheckRequestStatus.OPEN));
		when(requests.findByIncidentIdOrderByCreatedAtAsc(INCIDENT)).thenReturn(incidentRequests);
		var incidentWorks = List.of(work("w1", WorkItem.Status.READY));
		when(works.findByIncidentIdOrderByCreatedAtAsc(INCIDENT)).thenReturn(incidentWorks);
		assertThatThrownBy(() -> guard.requireNone(incident, ConfirmationContext.direct("u1")))
			.isInstanceOfSatisfying(ApiException.class, e -> {
				assertThat(e.getCode()).isEqualTo("CONFIRMATION_BLOCKED");
				assertThat((List<?>) e.getDetails().get("blockers")).hasSize(2);
			});
	}
}
