package com.fabcontext.api.domain.action;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.LinkedHashMap;

import com.fabcontext.api.domain.incident.Incident;
import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.FetchType;
import jakarta.persistence.ForeignKey;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
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

/** 사람이 실제로 수행한 조치. 추천은 수행 사실이 아니므로 여기 저장하지 않는다. */
@Entity
@Table(name = "incident_action", indexes = @Index(columnList = "incidentId"))
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class IncidentAction {

	@Id
	private String actionId;

	private String incidentId;

	/** 외래 키 제약 전용 읽기 연관 — 값은 incidentId로만 쓰고 읽는다(API 응답 모양을 바꾸지 않으려고 String을 남겼다) */
	@ManyToOne(fetch = FetchType.LAZY)
	@JoinColumn(name = "incidentId", insertable = false, updatable = false,
		foreignKey = @ForeignKey(name = "fk_incident_action_incident"))
	@Getter(AccessLevel.NONE)
	private Incident incidentRef;

	private String processCode;

	/** CHAMBER_CLEAN · PART_REPLACE · RECIPE_ADJUST · INSPECTION · OTHER 등 */
	private String actionType;

	@Column(length = 4000)
	private String description;

	private Instant performedAt;

	private String performedBy;

	@JdbcTypeCode(SqlTypes.JSON)
	private List<String> evidenceIds = new ArrayList<>();

	private boolean confirmedByUser;

	/** USER(직접 입력) · APPROVAL(승인된 AI 대리 기록) */
	private String source;

	private String approvalId;

	/** 연결된 분석·제안의 저장 시점 스냅샷. 연결 자체는 승인·실행이 아니다. */
	@JdbcTypeCode(SqlTypes.JSON)
	private Map<String, Object> relatedSource;

	@Enumerated(EnumType.STRING)
	private VerificationStatus verificationStatus;

	private long version;

	private Instant createdAt;

	@ElementCollection
	@CollectionTable(name = "action_verification", joinColumns = @JoinColumn(name = "action_id"))
	@OrderColumn(name = "seq")
	private List<ActionVerification> verifications = new ArrayList<>();

	@Version
	private long rowVersion;

	@Builder
	public IncidentAction(String actionId, String incidentId, String processCode, String actionType,
			String description, Instant performedAt, String performedBy, List<String> evidenceIds, String source,
			String approvalId, Instant createdAt, Map<String, Object> relatedSource) {
		this.actionId = actionId;
		this.incidentId = incidentId;
		this.processCode = processCode;
		this.actionType = actionType;
		this.description = description;
		this.performedAt = performedAt;
		this.performedBy = performedBy;
		this.evidenceIds = evidenceIds == null ? new ArrayList<>() : new ArrayList<>(evidenceIds);
		this.confirmedByUser = true;
		this.source = source;
		this.approvalId = approvalId;
		this.relatedSource = relatedSource == null ? null : new LinkedHashMap<>(relatedSource);
		this.verificationStatus = VerificationStatus.PENDING;
		this.version = 1;
		this.createdAt = createdAt;
	}

	void verify(ActionVerification verification) {
		verifications.add(verification);
		verificationStatus = verification.getOutcome();
		version++;
	}
}
