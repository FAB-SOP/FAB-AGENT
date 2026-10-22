package com.fabcontext.api.domain.notification;

import java.time.Instant;

import com.fabcontext.api.common.Ref;
import io.swagger.v3.oas.annotations.media.Schema;

public final class NotificationDtos {

	private NotificationDtos() {
	}

	@Schema(description = "사용자 알림. 화면 중복 표시는 notificationId로 거른다. 자유 입력 내용(질문·회신·사유)은 담지 않는다.")
	public record NotificationView(
			@Schema(example = "ntf_7m2k9q4x1b3c") String notificationId,
			@Schema(description = "수신자별 순번 = SSE event id. 재연결 시 Last-Event-ID로 쓴다", example = "42") String eventId,
			@Schema(example = "CHECK_REQUEST_REPLIED") NotificationType type,
			@Schema(example = "보낸 요청에 회신이 도착했습니다") String title,
			@Schema(description = "이동 대상 — 열 때 해당 리소스 API가 권한을 다시 검사한다") Ref target,
			@Schema(description = "이동 대상의 상위 리소스(회신 → CHECK_REQUEST, 작업 → INCIDENT 등). 없으면 null") Ref parent,
			@Schema(description = "맥락용 사건 ID. 알림 수신이 사건 조회 권한을 주지 않는다") String incidentId,
			@Schema(example = "EV-1024") String incidentDisplayId,
			@Schema(description = "알림을 일으킨 사용자. Agent·시스템이면 null") String actorUserId,
			Instant createdAt, Instant readAt, boolean read) {

		static NotificationView from(Notification n) {
			return new NotificationView(n.getNotificationId(), String.valueOf(n.getSeq()), n.getType(), n.getTitle(),
				n.target(), n.parent(), n.getIncidentId(), n.getIncidentDisplayId(), n.getActorUserId(),
				n.getCreatedAt(), n.getReadAt(), n.getReadAt() != null);
		}
	}

	@Schema(description = "미읽음 수와 현재 마지막 event id(연결 전 기준점)")
	public record UnreadCount(long unreadCount,
			@Schema(description = "지금까지 저장된 마지막 알림 순번. 알림이 없으면 \"0\"", example = "42") String latestEventId) {
	}

	public record ReadResult(String notificationId, Instant readAt, long unreadCount) {
	}
}
