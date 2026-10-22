package com.fabcontext.api.domain.incident;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.fabcontext.api.common.ApiException;
import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OrderColumn;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(name = "incident")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Incident {

	@Id
	private String incidentId;

	/** 화면 표시용 ID (EV-1009) */
	@Column(unique = true, nullable = false)
	private String displayId;

	private int displaySeq;

	private String title;

	private String anomalyType;

	private String severity;

	@Column(nullable = false)
	private String processCode;

	@Column(nullable = false)
	private String equipmentId;

	private String chamberId;

	private String lotId;

	@JdbcTypeCode(SqlTypes.JSON)
	private List<String> waferIds = new ArrayList<>();

	@JdbcTypeCode(SqlTypes.JSON)
	private List<String> runIds = new ArrayList<>();

	@JdbcTypeCode(SqlTypes.JSON)
	private List<String> sensorIds = new ArrayList<>();

	@JdbcTypeCode(SqlTypes.JSON)
	private Map<String, Object> metrics = new LinkedHashMap<>();

	@Column(length = 2000)
	private String summary;

	/** 시뮬레이터 이벤트 ID (중복 접수 방지) */
	@Column(unique = true)
	private String sourceEventId;

	private String source;

	private Instant detectedAt;

	@Enumerated(EnumType.STRING)
	@Setter
	private IncidentStatus status;

	/** 아직 성공으로 끝나지 않은 최신 작업(분석 중·대기·실패) */
	@Setter
	private String activeJobId;

	@Setter
	private String latestJobStatus;

	/** 사용자에게 보이는 사건 상태 버전 (expectedIncidentVersion 비교 대상) */
	private long version;

	@Version
	private long rowVersion;

	private Instant createdAt;

	private Instant updatedAt;

	@ElementCollection(fetch = FetchType.LAZY)
	@CollectionTable(name = "incident_timeline", joinColumns = @JoinColumn(name = "incident_id"))
	@OrderColumn(name = "seq")
	private List<TimelineEntry> timeline = new ArrayList<>();

	@Builder
	public Incident(String incidentId, int displaySeq, String title, String anomalyType, String severity,
			String processCode, String equipmentId, String chamberId, String lotId, List<String> waferIds,
			List<String> runIds, List<String> sensorIds, Map<String, Object> metrics, String summary,
			String sourceEventId, String source, Instant detectedAt, Instant createdAt) {
		this.incidentId = incidentId;
		this.displaySeq = displaySeq;
		this.displayId = "EV-" + displaySeq;
		this.title = title;
		this.anomalyType = anomalyType;
		this.severity = severity;
		this.processCode = processCode;
		this.equipmentId = equipmentId;
		this.chamberId = chamberId;
		this.lotId = lotId;
		this.waferIds = waferIds == null ? new ArrayList<>() : new ArrayList<>(waferIds);
		this.runIds = runIds == null ? new ArrayList<>() : new ArrayList<>(runIds);
		this.sensorIds = sensorIds == null ? new ArrayList<>() : new ArrayList<>(sensorIds);
		this.metrics = metrics == null ? new LinkedHashMap<>() : new LinkedHashMap<>(metrics);
		this.summary = summary;
		this.sourceEventId = sourceEventId;
		this.source = source;
		this.detectedAt = detectedAt;
		this.status = IncidentStatus.OPEN;
		this.version = 1;
		this.createdAt = createdAt;
		this.updatedAt = createdAt;
	}

	/** 타임라인에 기록하고 사건 버전을 올린다. */
	public void record(Instant at, String type, String actorId, String message, String refType, String refId) {
		timeline.add(new TimelineEntry(at, type, actorId, message, refType, refId));
		version++;
		updatedAt = at;
	}

	public void checkVersion(Long expected) {
		ApiException.checkVersion("incident", expected, version);
	}

	public void requireNotClosed() {
		if (status == IncidentStatus.CLOSED) {
			throw ApiException.conflict("INCIDENT_CLOSED", "종료된 사건에는 작업을 추가할 수 없습니다.",
				Map.of("incidentId", incidentId));
		}
	}

	public boolean isActive() {
		return status != IncidentStatus.CLOSED;
	}
}
