package com.fabcontext.api.domain.notification;

import java.time.Instant;

import com.fabcontext.api.common.Ref;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 사용자 한 명에게 저장된 알림. seq는 수신자별로 커밋 순서대로 1씩 증가하며 SSE event id로 쓴다
 * ({@link NotificationCursor} 행 잠금으로 같은 수신자의 발급을 직렬화한다).
 * 같은 원본 업무 이벤트(sourceKey)는 수신자당 한 번만 저장한다.
 */
@Entity
@Table(name = "user_notification",
	uniqueConstraints = {
		@UniqueConstraint(name = "uk_user_notification_source", columnNames = {"recipientUserId", "sourceKey"}),
		@UniqueConstraint(name = "uk_user_notification_seq", columnNames = {"recipientUserId", "seq"})},
	indexes = @Index(name = "ix_user_notification_unread", columnList = "recipientUserId,readAt"))
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Notification {

	@Id
	private String notificationId;

	@Column(nullable = false)
	private String recipientUserId;

	/** 수신자별 순번 — SSE id · 재연결 복구(Last-Event-ID) 기준 */
	@Column(nullable = false)
	private long seq;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false)
	private NotificationType type;

	/** 원본 업무 이벤트 식별자(종류:리소스 ID) — 수신자별 중복 저장 방지 */
	@Column(nullable = false, length = 200)
	private String sourceKey;

	@Column(nullable = false)
	private String title;

	@Column(nullable = false)
	private String targetType;

	@Column(nullable = false)
	private String targetId;

	/** 이동 대상의 상위 리소스(회신 → 확인 요청 등). 없으면 null */
	private String parentType;

	private String parentId;

	/** 맥락용 사건 ID. 알림 수신이 사건 조회 권한을 주지 않는다 */
	private String incidentId;

	private String incidentDisplayId;

	/** 알림을 일으킨 사용자(없으면 Agent · 시스템) */
	private String actorUserId;

	@Column(nullable = false)
	private Instant createdAt;

	private Instant readAt;

	Notification(String notificationId, String recipientUserId, long seq, NotificationDraft draft, Instant now) {
		this.notificationId = notificationId;
		this.recipientUserId = recipientUserId;
		this.seq = seq;
		this.type = draft.type();
		this.sourceKey = draft.sourceKey();
		this.title = draft.type().title;
		this.targetType = draft.type().targetType;
		this.targetId = draft.targetId();
		this.parentType = draft.parent() == null ? null : draft.parent().type();
		this.parentId = draft.parent() == null ? null : draft.parent().id();
		this.incidentId = draft.incidentId();
		this.incidentDisplayId = draft.incidentDisplayId();
		this.actorUserId = draft.actorUserId();
		this.createdAt = now;
	}

	public Ref target() {
		return new Ref(targetType, targetId);
	}

	public Ref parent() {
		return Ref.of(parentType, parentId);
	}
}
