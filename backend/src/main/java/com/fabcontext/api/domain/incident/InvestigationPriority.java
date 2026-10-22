package com.fabcontext.api.domain.incident;

import java.util.Objects;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/** 저장된 조사 우선순위. 탐지 severity·원인 분석 작업 상태와 독립된 값이다. */
@Embeddable
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class InvestigationPriority {

	public enum Status { NOT_EVALUATED, PENDING, COMPLETED, FAILED }
	public enum Level { HIGH, MEDIUM, LOW }

	@Enumerated(EnumType.STRING)
	@Column(name = "investigation_priority_status")
	private Status status;

	@Enumerated(EnumType.STRING)
	@Column(name = "investigation_priority_level")
	private Level level;

	@Column(name = "investigation_priority_provisional")
	@Getter(AccessLevel.NONE)
	private Boolean provisional;

	@Column(name = "investigation_priority_reason", length = 2000)
	private String reason;

	private InvestigationPriority(Status status, Level level, boolean provisional, String reason) {
		this.status = Objects.requireNonNull(status, "판정 상태가 필요합니다.");
		if (status == Status.COMPLETED) {
			Objects.requireNonNull(level, "완료된 판정에는 등급이 필요합니다.");
			requireReason(reason);
			if (provisional && level != Level.MEDIUM) {
				throw new IllegalArgumentException("잠정 판정은 MEDIUM만 가능합니다.");
			}
		}
		else if (level != null || provisional) {
			throw new IllegalArgumentException("완료 전·실패 상태에는 등급이나 잠정 판정을 저장할 수 없습니다.");
		}
		if (status == Status.FAILED) requireReason(reason);
		this.level = level;
		this.provisional = provisional;
		this.reason = reason;
	}

	public static InvestigationPriority notEvaluated() {
		return new InvestigationPriority(Status.NOT_EVALUATED, null, false, null);
	}

	public static InvestigationPriority pending() {
		return new InvestigationPriority(Status.PENDING, null, false, null);
	}

	public static InvestigationPriority completed(Level level, boolean provisional, String reason) {
		return new InvestigationPriority(Status.COMPLETED, level, provisional, reason);
	}

	public static InvestigationPriority failed(String reason) {
		return new InvestigationPriority(Status.FAILED, null, false, reason);
	}

	public boolean isProvisional() {
		return Boolean.TRUE.equals(provisional);
	}

	private static void requireReason(String reason) {
		if (reason == null || reason.isBlank() || reason.length() > 2000) {
			throw new IllegalArgumentException("판정·실패 사유는 1~2000자여야 합니다.");
		}
	}
}
