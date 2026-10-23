package com.fabcontext.api.domain.job;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.ForeignKey;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/** 업무 상태와 같은 트랜잭션으로 저장하는 수신 기록 — 재전송 · 서버 재시작 뒤에도 같은 이벤트는 한 번만 반영한다. */
@Entity
@Table(name = "processed_agent_event", indexes = @Index(columnList = "jobId"))
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ProcessedAgentEvent {

	@Id
	private String eventId;

	@Column(nullable = false)
	private String jobId;

	@ManyToOne(fetch = FetchType.LAZY)
	@JoinColumn(name = "jobId", insertable = false, updatable = false,
		foreignKey = @ForeignKey(name = "fk_processed_agent_event_job"))
	@Getter(AccessLevel.NONE)
	private Job jobRef;

	private int attempt;

	@Column(nullable = false)
	private String eventType;

	@Column(nullable = false)
	private Instant processedAt;

	public ProcessedAgentEvent(String eventId, String jobId, int attempt, String eventType, Instant processedAt) {
		this.eventId = eventId;
		this.jobId = jobId;
		this.attempt = attempt;
		this.eventType = eventType;
		this.processedAt = processedAt;
	}
}
