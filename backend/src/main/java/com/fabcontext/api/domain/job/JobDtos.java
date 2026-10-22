package com.fabcontext.api.domain.job;

import java.time.Instant;
import java.util.Map;

import com.fabcontext.api.common.Ref;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public final class JobDtos {

	private JobDtos() {
	}

	public record JobError(String code, String message) {
	}

	public record JobView(String jobId, String incidentId, JobKind kind, JobStatus status, int attempt,
			String threadId, String parentJobId, String question, String scope, Ref waitingRef, Ref resultRef,
			JobError error, String requestedBy, Instant createdAt, Instant startedAt, Instant finishedAt,
			Instant updatedAt) {

		public static JobView from(Job j) {
			return new JobView(j.getJobId(), j.getIncidentId(), j.getKind(), j.getStatus(), j.getAttempt(),
				j.getThreadId(), j.getParentJobId(), j.getQuestion(), j.getScope(), j.waitingRef(), j.resultRef(),
				j.getErrorCode() == null ? null : new JobError(j.getErrorCode(), j.getErrorMessage()),
				j.getRequestedBy(), j.getCreatedAt(), j.getStartedAt(), j.getFinishedAt(), j.getUpdatedAt());
		}
	}

	/** 202 Accepted 응답: 상태는 statusUrl(getJob) 또는 streamUrl(SSE)로 확인 */
	public record Accepted(String jobId, String incidentId, String displayId, JobKind kind, JobStatus status,
			String statusUrl, String streamUrl, boolean duplicate) {

		public static Accepted of(Job job, String displayId, boolean duplicate) {
			return new Accepted(job == null ? null : job.getJobId(), job == null ? null : job.getIncidentId(),
				displayId, job == null ? null : job.getKind(), job == null ? null : job.getStatus(),
				job == null ? null : "/api/jobs/" + job.getJobId(),
				job == null ? null : "/api/incidents/" + job.getIncidentId() + "/stream", duplicate);
		}

		public static Accepted forIncident(String incidentId, String displayId, Job job, boolean duplicate) {
			return new Accepted(job == null ? null : job.getJobId(), incidentId, displayId,
				job == null ? null : job.getKind(), job == null ? null : job.getStatus(),
				job == null ? null : "/api/jobs/" + job.getJobId(), "/api/incidents/" + incidentId + "/stream",
				duplicate);
		}
	}

	/** 추가 질문 (DEEP 작업) */
	public record Query(@NotBlank @Size(max = 2000) String question,
			@Pattern(regexp = "AUTO|CURRENT|UPSTREAM") String scope, @NotNull Long expectedIncidentVersion) {
	}

	public record BasicAnalysisRequest(@NotNull Long expectedIncidentVersion, @Size(max = 1000) String reason) {
	}

	public record JobEvent(String jobId, JobKind kind, JobStatus status, int attempt, Ref waitingRef,
			Ref resultRef, JobError error) {

		static JobEvent from(Job j) {
			return new JobEvent(j.getJobId(), j.getKind(), j.getStatus(), j.getAttempt(), j.waitingRef(),
				j.resultRef(), j.getErrorCode() == null ? null : new JobError(j.getErrorCode(), j.getErrorMessage()));
		}
	}

	public record JobQueuedEvent(String jobId) {
	}

	static Map<String, Object> none() {
		return Map.of();
	}
}
