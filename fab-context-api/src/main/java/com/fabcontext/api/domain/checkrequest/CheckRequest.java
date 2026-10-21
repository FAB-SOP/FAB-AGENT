package com.fabcontext.api.domain.checkrequest;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.fabcontext.api.common.ApiException;
import com.fabcontext.api.domain.incident.Incident;
import jakarta.persistence.Column;
import jakarta.persistence.FetchType;
import jakarta.persistence.ForeignKey;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/** 승인된 제안으로 발송된 다른 공정 담당자 확인 요청 */
@Entity
@Table(name = "check_request")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class CheckRequest {

	@Id
	private String requestId;

	private String incidentId;

	/** 외래 키 제약 전용 읽기 연관 — 값은 incidentId로만 쓰고 읽는다(API 응답 모양을 바꾸지 않으려고 String을 남겼다) */
	@ManyToOne(fetch = FetchType.LAZY)
	@JoinColumn(name = "incidentId", insertable = false, updatable = false,
		foreignKey = @ForeignKey(name = "fk_check_request_incident"))
	@Getter(AccessLevel.NONE)
	private Incident incidentRef;

	/** 사건 공정 */
	private String processCode;

	/** 확인을 요청받은 공정 */
	private String targetProcessCode;

	/** CROSS_PROCESS(다른 공정 담당 확인) · MAINTENANCE(같은 공정 정비 담당 점검) */
	private String requestType;

	/**
	 * 이 요청을 만든 승인 — ID 문자열만 둔다(엔티티 관계로 Approval을 품으면 checkrequest → approval 패키지 순환이 생긴다).
	 * 외래 키 fk_check_request_approval은 schema.sql이 건다
	 */
	private String approvalId;

	@Column(length = 2000)
	private String question;

	@JdbcTypeCode(SqlTypes.JSON)
	private List<String> evidenceIds = new ArrayList<>();

	/** 요청 대상 설비(PM) — 제안 payload.equipmentId */
	private String equipmentId;

	/** 원인 후보(원인 매트릭스 ID) — 확정이 아니다 */
	@JdbcTypeCode(SqlTypes.JSON)
	private List<String> causeIds = new ArrayList<>();

	/** 요청 브리프({@link RequestBrief}) — 승인된 제안의 payload.brief를 그대로 복사. 수동 요청은 없을 수 있다 */
	@JdbcTypeCode(SqlTypes.JSON)
	private Map<String, Object> brief;

	private String requesterUserId;

	private String ownerUserId;

	@Enumerated(EnumType.STRING)
	private CheckRequestStatus status;

	private long version;

	/** 회신을 기다리는 Agent 작업 (회신 시 같은 threadId로 RESUME) */
	@Setter
	private String waitingJobId;

	private String threadId;

	private Instant dueAt;

	private Instant createdAt;

	private Instant repliedAt;

	@Column(length = 2000)
	private String declineReason;

	@Version
	private long rowVersion;

	@Builder
	public CheckRequest(String requestId, String requestType, String incidentId, String processCode,
			String targetProcessCode, String approvalId, String question, List<String> evidenceIds, String requesterUserId,
			String ownerUserId, String threadId, Instant dueAt, Instant createdAt, String equipmentId, List<String> causeIds,
			Map<String, Object> brief) {
		this.requestId = requestId;
		this.requestType = requestType == null ? "CROSS_PROCESS" : requestType;
		this.incidentId = incidentId;
		this.processCode = processCode;
		this.targetProcessCode = targetProcessCode;
		this.approvalId = approvalId;
		this.question = question;
		this.evidenceIds = evidenceIds == null ? new ArrayList<>() : new ArrayList<>(evidenceIds);
		this.equipmentId = equipmentId;
		this.causeIds = causeIds == null ? new ArrayList<>() : new ArrayList<>(causeIds);
		this.brief = brief == null ? null : new LinkedHashMap<>(brief);
		this.requesterUserId = requesterUserId;
		this.ownerUserId = ownerUserId;
		this.status = CheckRequestStatus.OPEN;
		this.version = 1;
		this.threadId = threadId;
		this.dueAt = dueAt;
		this.createdAt = createdAt;
	}

	/** 수락하지 않고 바로 회신해도 된다(회신이 곧 수락) */
	void markReplied(Instant at) {
		requireStatus(CheckRequestStatus.AWAITING_REPLY);
		status = CheckRequestStatus.REPLIED;
		repliedAt = at;
		version++;
	}

	void accept() {
		requireStatus(java.util.Set.of(CheckRequestStatus.OPEN));
		status = CheckRequestStatus.ACCEPTED;
		version++;
	}

	void decline(String reason) {
		requireStatus(java.util.Set.of(CheckRequestStatus.OPEN));
		status = CheckRequestStatus.DECLINED;
		declineReason = reason;
		version++;
	}

	private void requireStatus(java.util.Set<CheckRequestStatus> allowed) {
		if (!allowed.contains(status)) {
			throw ApiException.conflict("REQUEST_NOT_OPEN", "이미 처리된 요청입니다.",
				Map.of("status", status.name(), "currentVersion", version));
		}
	}
}
