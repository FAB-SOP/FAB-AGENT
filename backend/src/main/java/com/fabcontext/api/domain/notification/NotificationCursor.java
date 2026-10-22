package com.fabcontext.api.domain.notification;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 수신자별 마지막 알림 순번. 발급 트랜잭션이 이 행을 잠그고 커밋까지 쥐므로 같은 수신자의 seq는 커밋 순서와 같다 —
 * 재연결 시 "Last-Event-ID보다 큰 seq"만 다시 보내도 늦게 커밋된 알림을 건너뛰지 않는다.
 */
@Entity
@Table(name = "user_notification_cursor")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class NotificationCursor {

	@Id
	private String userId;

	private long lastSeq;

	long next() {
		return ++lastSeq;
	}
}
