package com.fabcontext.api.domain.notification;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

public final class NotificationRepositories {

	private NotificationRepositories() {
	}

	public interface NotificationRepository extends JpaRepository<Notification, String> {

		Optional<Notification> findByNotificationIdAndRecipientUserId(String notificationId, String recipientUserId);

		boolean existsByRecipientUserIdAndSourceKey(String recipientUserId, String sourceKey);

		long countByRecipientUserIdAndReadAtIsNull(String recipientUserId);

		/** 최신순 목록 — seq 기준 keyset(더보기 중 새 알림이 앞에 붙어도 밀리거나 겹치지 않는다) */
		@Query("""
			select n from Notification n
			where n.recipientUserId = :userId and n.seq < :beforeSeq and (:unreadOnly = false or n.readAt is null)
			order by n.seq desc""")
		List<Notification> pageDesc(String userId, long beforeSeq, boolean unreadOnly, Pageable page);

		/** SSE 전달 · 재연결 복구 — afterSeq 다음부터 오래된 순 */
		List<Notification> findByRecipientUserIdAndSeqGreaterThanOrderBySeqAsc(String recipientUserId, long afterSeq,
				Pageable page);

		/** 반복 호출에 안전한 읽음 — 처음 한 번만 readAt을 기록한다 */
		@Modifying(flushAutomatically = true, clearAutomatically = true)
		@Query("""
			update Notification n set n.readAt = :now
			where n.notificationId = :notificationId and n.recipientUserId = :userId and n.readAt is null""")
		int markRead(String notificationId, String userId, Instant now);
	}

	public interface NotificationCursorRepository extends JpaRepository<NotificationCursor, String> {

		/** 처음 받는 수신자의 순번 행 — 동시에 만들어도 실패하지 않는다 */
		@Modifying
		@Query(value = """
			insert into {h-schema}user_notification_cursor (user_id, last_seq) values (:userId, 0)
			on conflict (user_id) do nothing""", nativeQuery = true)
		void ensure(String userId);

		@Lock(LockModeType.PESSIMISTIC_WRITE)
		@Query("select c from NotificationCursor c where c.userId = :userId")
		Optional<NotificationCursor> lock(String userId);
	}
}
