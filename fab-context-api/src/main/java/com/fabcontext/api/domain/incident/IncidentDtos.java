package com.fabcontext.api.domain.incident;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import com.fabcontext.api.common.Ref;
import com.fabcontext.api.domain.job.Job;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public final class IncidentDtos {

	private IncidentDtos() {
	}

	public record IncidentSummary(String incidentId, String displayId, String title, String anomalyType,
			String severity, String processCode, String equipmentId, String chamberId, String lotId,
			List<String> waferIds, IncidentStatus status, long version, String activeJobId, String jobStatus,
			Instant detectedAt, Instant updatedAt) {

		public static IncidentSummary from(Incident i) {
			return new IncidentSummary(i.getIncidentId(), i.getDisplayId(), i.getTitle(), i.getAnomalyType(),
				i.getSeverity(), i.getProcessCode(), i.getEquipmentId(), i.getChamberId(), i.getLotId(),
				List.copyOf(i.getWaferIds()), i.getStatus(), i.getVersion(), i.getActiveJobId(),
				i.getLatestJobStatus(), i.getDetectedAt(), i.getUpdatedAt());
		}
	}

	public record TimelineItem(Instant at, String type, String actorId, String message, Ref ref) {

		static TimelineItem from(TimelineEntry e) {
			return new TimelineItem(e.getAt(), e.getType(), e.getActorId(), e.getMessage(),
				Ref.of(e.getRefType(), e.getRefId()));
		}
	}

	public record ActiveJob(String jobId, String kind, String status, int attempt, Ref waitingRef) {

		public static ActiveJob from(Job job) {
			return job == null ? null
				: new ActiveJob(job.getJobId(), job.getKind().name(), job.getStatus().name(), job.getAttempt(),
					job.waitingRef());
		}
	}

	public record IncidentDetail(String incidentId, String displayId, String title, String anomalyType,
			String severity, String summary, String processCode, String equipmentId, String chamberId,
			String lotId, List<String> waferIds, List<String> runIds, List<String> sensorIds,
			Map<String, Object> metrics, IncidentStatus status, long version, String activeJobId,
			ActiveJob activeJob, Instant detectedAt, Instant createdAt, Instant updatedAt,
			List<TimelineItem> timeline) {

		public static IncidentDetail from(Incident i, Job activeJob) {
			return new IncidentDetail(i.getIncidentId(), i.getDisplayId(), i.getTitle(), i.getAnomalyType(),
				i.getSeverity(), i.getSummary(), i.getProcessCode(), i.getEquipmentId(), i.getChamberId(),
				i.getLotId(), List.copyOf(i.getWaferIds()), List.copyOf(i.getRunIds()),
				List.copyOf(i.getSensorIds()), Map.copyOf(i.getMetrics()), i.getStatus(), i.getVersion(),
				i.getActiveJobId(), ActiveJob.from(activeJob), i.getDetectedAt(), i.getCreatedAt(),
				i.getUpdatedAt(), i.getTimeline().stream().map(TimelineItem::from).toList());
		}
	}

	/** 관리자 상태 전이: 보류 · 종료 · 보류 재개 */
	public record IncidentTransition(@NotNull IncidentStatus targetStatus,
			@NotBlank @Size(max = 1000) String reason, List<String> evidenceIds, @NotNull Long expectedVersion) {
	}
}
