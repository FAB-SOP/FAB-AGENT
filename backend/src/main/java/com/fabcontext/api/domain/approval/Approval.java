package com.fabcontext.api.domain.approval;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.fabcontext.api.common.ApiException;
import com.fabcontext.api.common.Ref;
import com.fabcontext.api.domain.incident.Incident;
import com.fabcontext.api.domain.job.Job;
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

/**
 * 실행할 정확한 내용(payload)을 저장한 변경 제안. 승인 시 저장된 내용만 실행하며,
 * 수정이 필요하면 기존 제안을 덮어쓰지 않고 거절 후 새 제안을 만든다.
 */
@Entity
@Table(name = "approval")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Approval {

	@Id
	private String approvalId;

	private String incidentId;

	/** 외래 키 제약 전용 읽기 연관 — 값은 incidentId로만 쓰고 읽는다(API 응답 모양을 바꾸지 않으려고 String을 남겼다) */
	@ManyToOne(fetch = FetchType.LAZY)
	@JoinColumn(name = "incidentId", insertable = false, updatable = false,
		foreignKey = @ForeignKey(name = "fk_approval_incident"))
	@Getter(AccessLevel.NONE)
	private Incident incidentRef;

	private String processCode;

	@Enumerated(EnumType.STRING)
	private ApprovalActionType actionType;

	@JdbcTypeCode(SqlTypes.JSON)
	private Map<String, Object> payload = new LinkedHashMap<>();

	@Column(length = 2000)
	private String rationale;

	@JdbcTypeCode(SqlTypes.JSON)
	private List<String> evidenceIds = new ArrayList<>();

	/** 승인자가 승인할 때 입력한 값(예: 보고서 확정의 원인 분류) — 해시 밖의 승인자 결정이며, 실행은 이 저장값만 쓴다 */
	@JdbcTypeCode(SqlTypes.JSON)
	private Map<String, Object> decisionInput;

	/** payload 무결성 해시 (sha256 of canonical {actionType, incidentId, payload}) */
	private String actionHash;

	@Enumerated(EnumType.STRING)
	private ApprovalStatus status;

	private long version;

	private Instant expiresAt;

	/** 승인을 기다리는 Agent 작업. 수동 제안이면 null */
	private String jobId;

	/** 외래 키 제약 전용 읽기 연관 — 값은 jobId로만 쓰고 읽는다(API 응답 모양을 바꾸지 않으려고 String을 남겼다) */
	@ManyToOne(fetch = FetchType.LAZY)
	@JoinColumn(name = "jobId", insertable = false, updatable = false,
		foreignKey = @ForeignKey(name = "fk_approval_job"))
	@Getter(AccessLevel.NONE)
	private Job jobRef;

	/** AGENT · USER */
	private String source;

	private String createdBy;

	private Instant createdAt;

	private String decidedBy;

	private Instant decidedAt;

	@Column(length = 2000)
	private String decisionComment;

	private String executionRefType;

	private String executionRefId;

	private String resumeJobId;

	/** Agent 제안의 멱등 키 — 같은 키로 다시 제안하면 이 승인을 돌려준다 */
	private String proposalKey;

	/** 승인된 변경을 실제로 실행한 시각 · 실행 요청 멱등 키 (Agent 제안은 승인 뒤 /internal/approved-actions/*로 실행) */
	private Instant executedAt;

	private String executionKey;

	private Integer incidentVersionAtProposal;

	@Version
	private long rowVersion;

	@Builder
	public Approval(String approvalId, String incidentId, String processCode, ApprovalActionType actionType,
			Map<String, Object> payload, String rationale, List<String> evidenceIds, String actionHash,
			Instant expiresAt, String jobId, String source, String createdBy, Instant createdAt,
			Integer incidentVersionAtProposal) {
		this.approvalId = approvalId;
		this.incidentId = incidentId;
		this.processCode = processCode;
		this.actionType = actionType;
		this.payload = new LinkedHashMap<>(payload);
		this.rationale = rationale;
		this.evidenceIds = evidenceIds == null ? new ArrayList<>() : new ArrayList<>(evidenceIds);
		this.actionHash = actionHash;
		this.status = ApprovalStatus.PENDING;
		this.version = 1;
		this.expiresAt = expiresAt;
		this.jobId = jobId;
		this.source = source;
		this.createdBy = createdBy;
		this.createdAt = createdAt;
		this.incidentVersionAtProposal = incidentVersionAtProposal;
	}

	public void assignProposalKey(String key) {
		this.proposalKey = key;
	}

	public boolean isExecuted() {
		return executionRefId != null;
	}

	void markExecuted(Ref execution, String key, Instant at) {
		executionRefType = execution.type();
		executionRefId = execution.id();
		executionKey = key;
		executedAt = at;
	}

	public Ref executionRef() {
		return Ref.of(executionRefType, executionRefId);
	}

	/** 만료 시각이 지났으면 EXPIRED로 바꾸고 true */
	public boolean expireIfDue(Instant now) {
		if (status == ApprovalStatus.PENDING && expiresAt != null && now.isAfter(expiresAt)) {
			status = ApprovalStatus.EXPIRED;
			version++;
			return true;
		}
		return false;
	}

	void approve(String userId, String comment, Ref execution, String resumeJobId, Instant at) {
		requirePending();
		status = ApprovalStatus.APPROVED;
		decidedBy = userId;
		decidedAt = at;
		decisionComment = comment;
		if (execution != null) {
			executionRefType = execution.type();
			executionRefId = execution.id();
		}
		this.resumeJobId = resumeJobId;
		version++;
	}

	/** 승인 전에 승인자 입력을 남긴다 — 수동 승인은 바로 실행되고, Agent 대기 승인은 재개 뒤 이 값으로 실행된다 */
	void recordDecisionInput(Map<String, Object> input) {
		requirePending();
		decisionInput = input == null ? null : new LinkedHashMap<>(input);
	}

	void reject(String userId, String comment, Instant at) {
		requirePending();
		status = ApprovalStatus.REJECTED;
		decidedBy = userId;
		decidedAt = at;
		decisionComment = comment;
		version++;
	}

	void requirePending() {
		if (status != ApprovalStatus.PENDING) {
			throw ApiException.conflict("APPROVAL_NOT_PENDING", "이미 처리되었거나 만료된 제안입니다. 최신 내용을 다시 조회하세요.",
				Map.of("status", status.name(), "currentVersion", version));
		}
	}
}
