package com.fabcontext.api.domain.job;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

import com.fabcontext.api.common.Ref;
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
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "job", indexes = @Index(columnList = "incidentId"))
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Job {

	@Id
	private String jobId;

	@Column(nullable = false)
	private String incidentId;

	/** 외래 키 제약 전용 읽기 연관 — 값은 incidentId로만 쓰고 읽는다(API 응답 모양을 바꾸지 않으려고 String을 남겼다) */
	@ManyToOne(fetch = FetchType.LAZY)
	@JoinColumn(name = "incidentId", insertable = false, updatable = false,
		foreignKey = @ForeignKey(name = "fk_job_incident"))
	@Getter(AccessLevel.NONE)
	private Incident incidentRef;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false)
	private JobKind kind;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false)
	private JobStatus status;

	private int attempt;

	/** Agent 대화·체크포인트 식별자. RESUME 작업은 같은 threadId를 잇는다. */
	private String threadId;

	private String parentJobId;

	@Column(length = 2000)
	private String question;

	private String scope;

	@JdbcTypeCode(SqlTypes.JSON)
	private Map<String, Object> input = new LinkedHashMap<>();

	private String waitingRefType;

	private String waitingRefId;

	private String resultRefType;

	private String resultRefId;

	private String errorCode;

	@Column(length = 1000)
	private String errorMessage;

	private String requestedBy;

	/** 외부 Agent Worker의 작업 확보 — 같은 attempt를 두 Worker가 처리하지 않게 한다 */
	private String claimedBy;

	private Integer claimedAttempt;

	private Instant leaseUntil;

	private Instant createdAt;

	private Instant startedAt;

	private Instant finishedAt;

	private Instant updatedAt;

	@Version
	private long rowVersion;

	@Builder
	public Job(String jobId, String incidentId, JobKind kind, int attempt, String threadId, String parentJobId,
			String question, String scope, Map<String, Object> input, String requestedBy, Instant createdAt) {
		this.jobId = jobId;
		this.incidentId = incidentId;
		this.kind = kind;
		this.status = JobStatus.QUEUED;
		this.attempt = attempt;
		this.threadId = threadId;
		this.parentJobId = parentJobId;
		this.question = question;
		this.scope = scope;
		this.input = input == null ? new LinkedHashMap<>() : new LinkedHashMap<>(input);
		this.requestedBy = requestedBy;
		this.createdAt = createdAt;
		this.updatedAt = createdAt;
	}

	/** 임대 중인 같거나 더 큰 attempt가 있으면 false */
	public boolean claim(int attempt, String workerId, Instant now, java.time.Duration lease) {
		if (claimedBy != null && claimedAttempt != null && claimedAttempt >= attempt && leaseUntil != null
				&& leaseUntil.isAfter(now)) {
			return false;
		}
		claimedBy = workerId;
		claimedAttempt = attempt;
		leaseUntil = now.plus(lease);
		this.attempt = Math.max(this.attempt, attempt);
		updatedAt = now;
		return true;
	}

	public void putInput(String key, Object value) {
		input.put(key, value);
	}

	/** 외부 Agent가 재시도를 잡으면 같은 작업을 다시 대기열에 둔다 */
	void requeue(int nextAttempt, Instant at) {
		status = JobStatus.QUEUED;
		attempt = nextAttempt;
		updatedAt = at;
	}

	public Ref waitingRef() {
		return Ref.of(waitingRefType, waitingRefId);
	}

	public Ref resultRef() {
		return Ref.of(resultRefType, resultRefId);
	}

	void start(Instant at) {
		status = JobStatus.RUNNING;
		startedAt = at;
		updatedAt = at;
	}

	void succeed(Ref result, Instant at) {
		status = JobStatus.SUCCEEDED;
		waitingRefType = null;
		waitingRefId = null;
		if (result != null) {
			resultRefType = result.type();
			resultRefId = result.id();
		}
		finishedAt = at;
		updatedAt = at;
	}

	void fail(String code, String message, Instant at) {
		status = JobStatus.FAILED;
		errorCode = code;
		errorMessage = message;
		finishedAt = at;
		updatedAt = at;
	}

	void waitFor(JobStatus waitingStatus, Ref ref, Instant at) {
		status = waitingStatus;
		waitingRefType = ref.type();
		waitingRefId = ref.id();
		updatedAt = at;
	}
}
