package com.fabcontext.api.domain.events;

import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;

import com.fabcontext.api.common.Ids;
import com.fabcontext.api.domain.incident.Incident;
import com.fabcontext.api.domain.incident.IncidentRepository;
import com.fabcontext.api.common.stream.IncidentStreamHub;
import com.fabcontext.api.domain.job.Job;
import com.fabcontext.api.domain.job.JobDtos.Accepted;
import com.fabcontext.api.domain.job.JobKind;
import com.fabcontext.api.domain.job.JobRepository;
import com.fabcontext.api.domain.job.JobService;
import com.fabcontext.api.domain.notification.NotificationEvents;
import com.fabcontext.api.domain.processdata.DataVersion;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class EventService {

	private static final Map<String, String> TITLES = Map.of(
		"THICKNESS_OOC_R1", "공정 후 두께 관리한계 이탈",
		"THICKNESS_OUT_OF_SPEC", "CMP 후 두께 규격 이탈",
		"DEP_RATE_DRIFT", "CVD 증착 속도 드리프트",
		"PRESSURE_FLUCTUATION", "챔버 압력 변동",
		"RF_REFLECTED_SPIKE", "RF 반사전력 급증",
		"SLURRY_FLOW_DROP", "슬러리 유량 저하",
		"PAD_TEMP_DRIFT", "CMP 패드 온도 드리프트");

	private final IncidentRepository incidentRepository;
	private final JobRepository jobRepository;
	private final JobService jobService;
	private final IncidentStreamHub streamHub;
	private final NotificationEvents notificationEvents;
	private final DataVersion dataVersion;
	private final Clock clock;

	public record IngestResult(Accepted accepted, boolean created) {
	}

	/** 새 이벤트 → 사건 생성 + BASIC 분석 투입(202). 이미 받은 eventId → 기존 사건 반환(200). */
	@Transactional
	public synchronized IngestResult ingest(AnomalyEvent event) {
		Optional<Incident> existing = incidentRepository.findBySourceEventId(event.eventId());
		if (existing.isPresent()) {
			Incident incident = existing.get();
			Job job = incident.getActiveJobId() == null ? null
				: jobRepository.findById(incident.getActiveJobId()).orElse(null);
			return new IngestResult(Accepted.forIncident(incident.getIncidentId(), incident.getDisplayId(), job, true),
				false);
		}
		Instant now = Instant.now(clock);
		Incident incident = Incident.builder()
			.incidentId(Ids.next("inc"))
			.displaySeq(incidentRepository.findMaxDisplaySeq() + 1)
			.title(event.title() != null ? event.title() : TITLES.getOrDefault(event.anomalyType(), event.anomalyType()))
			.anomalyType(event.anomalyType())
			.severity(event.severity() == null ? "MEDIUM" : event.severity())
			.processCode(event.processCode())
			.equipmentId(event.equipmentId())
			.chamberId(event.chamberId())
			.lotId(event.lotId())
			.waferIds(event.waferIds())
			.runIds(event.runIds())
			.sensorIds(event.sensorIds())
			.metrics(event.metrics())
			.summary(event.summary())
			.sourceEventId(event.eventId())
			.source(event.source() == null ? "simulator" : event.source())
			.detectedAt(event.detectedAt())
			.createdAt(now)
			.build();
		incident.record(now, "DETECTED", "svc_simulator",
			"이상 이벤트 접수 (" + event.anomalyType() + ", eventId " + event.eventId() + ")", "EVENT", event.eventId());
		incident = incidentRepository.save(incident);
		notificationEvents.incidentCreated(incident);
		dataVersion.bump();
		// 사건 저장 즉시 화면에 표시하고 기본 분석은 비동기로 병행한다.
		Job job = jobService.enqueue(incident, JobKind.BASIC, "svc_simulator", null, null, Map.of(), null, null);
		streamHub.publish(incident.getIncidentId(), "incident.updated",
			Map.of("status", incident.getStatus().name(), "version", incident.getVersion()));
		return new IngestResult(Accepted.forIncident(incident.getIncidentId(), incident.getDisplayId(), job, false), true);
	}
}
