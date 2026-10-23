package com.fabcontext.api.domain.incident;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import com.fabcontext.api.common.Ref;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public final class IncidentDtos {

	private IncidentDtos() {
	}

	public record IncidentSummary(String incidentId, String displayId, String title, String anomalyType,
			String severity, String processCode, String equipmentId, String chamberId, String lotId,
			List<String> waferIds, IncidentStatus status, long version, String activeJobId, String jobStatus,
			Instant detectedAt, Instant updatedAt, InvestigationPriorityView investigationPriority) {

		public static IncidentSummary from(Incident i) {
			return new IncidentSummary(i.getIncidentId(), i.getDisplayId(), i.getTitle(), i.getAnomalyType(),
				i.getSeverity(), i.getProcessCode(), i.getEquipmentId(), i.getChamberId(), i.getLotId(),
				List.copyOf(i.getWaferIds()), i.getStatus(), i.getVersion(), i.getActiveJobId(),
				i.getLatestJobStatus(), i.getDetectedAt(), i.getUpdatedAt(), InvestigationPriorityView.from(i));
		}
	}

	/** 목록·상세의 공통 계약. null 저장값도 항상 명시적 NOT_EVALUATED 객체로 반환한다. */
	public record InvestigationPriorityView(InvestigationPriority.Status status, InvestigationPriority.Level level,
			boolean provisional, String reason) {

		static InvestigationPriorityView from(Incident incident) {
			InvestigationPriority priority = incident.getInvestigationPriority();
			return new InvestigationPriorityView(priority.getStatus(), priority.getLevel(), priority.isProvisional(),
				priority.getReason());
		}
	}

	public record TimelineItem(Instant at, String type, String actorId, String message, Ref ref) {

		static TimelineItem from(TimelineEntry e) {
			return new TimelineItem(e.getAt(), e.getType(), e.getActorId(), e.getMessage(),
				Ref.of(e.getRefType(), e.getRefId()));
		}
	}

	/** 진행 중 작업 요약 — {@link ActiveJobLookup}(job 패키지 구현)이 만든다 */
	public record ActiveJob(String jobId, String kind, String status, int attempt, Ref waitingRef) {
	}

	public record IncidentDetail(String incidentId, String displayId, String title, String anomalyType,
			String severity, String summary, String processCode, String equipmentId, String chamberId,
			String lotId, List<String> waferIds, List<String> runIds, List<String> sensorIds,
			Map<String, Object> metrics, IncidentStatus status, long version, String activeJobId,
			ActiveJob activeJob, Instant detectedAt, Instant createdAt, Instant updatedAt,
			List<TimelineItem> timeline, InvestigationPriorityView investigationPriority, List<ActiveJob> waitingJobs) {

		public static IncidentDetail from(Incident i, ActiveJob activeJob) {
			return from(i, activeJob, List.of());
		}

		public static IncidentDetail from(Incident i, ActiveJob activeJob, List<ActiveJob> waitingJobs) {
			return new IncidentDetail(i.getIncidentId(), i.getDisplayId(), i.getTitle(), i.getAnomalyType(),
				i.getSeverity(), i.getSummary(), i.getProcessCode(), i.getEquipmentId(), i.getChamberId(),
				i.getLotId(), List.copyOf(i.getWaferIds()), List.copyOf(i.getRunIds()),
				List.copyOf(i.getSensorIds()), Map.copyOf(i.getMetrics()), i.getStatus(), i.getVersion(),
				i.getActiveJobId(), activeJob, i.getDetectedAt(), i.getCreatedAt(),
				i.getUpdatedAt(), i.getTimeline().stream().map(TimelineItem::from).toList(), InvestigationPriorityView.from(i), List.copyOf(waitingJobs));
		}
	}

	/** 관리자 상태 전이: 보류 · 종료 · 보류 재개 */
	public record IncidentTransition(@NotNull IncidentStatus targetStatus,
			@NotBlank @Size(max = 1000) String reason, List<String> evidenceIds, @NotNull Long expectedVersion) {
	}
}
