package com.fabcontext.api.domain.notification;

import com.fabcontext.api.auth.AuthUser;
import com.fabcontext.api.auth.SecurityConfig;
import com.fabcontext.api.common.ApiResponse;
import com.fabcontext.api.common.PageResponse;
import com.fabcontext.api.domain.notification.NotificationDtos.NotificationView;
import com.fabcontext.api.domain.notification.NotificationDtos.ReadResult;
import com.fabcontext.api.domain.notification.NotificationDtos.UnreadCount;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.enums.ParameterIn;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * 로그인 사용자 본인의 알림만 다룬다 — 경로 · 파라미터에 userId가 없고 세션 주체로만 찾는다.
 * 사건 SSE(/api/incidents/{id}/stream)는 사건 상세 진행 갱신, 이 SSE는 알림 전달용이다.
 */
@Tag(name = "notifications", description = """
	사용자 알림. 인증: 세션 쿠키(FABSESSION), 쓰기는 X-CSRF-TOKEN. 항상 로그인 사용자 본인의 알림만 조회·구독한다.
	알림은 DB에 먼저 저장되고 커밋 후 사용자 SSE로 전달된다(연결이 없어도 남는다).
	읽음은 표시 상태일 뿐 업무 수락·회신·처리 완료·추가 분석·조사 종료를 실행하지 않는다.
	target은 이동할 리소스 타입·ID이며, 그 화면의 API가 권한을 다시 검사한다(알림 수신이 사건 접근 권한을 주지 않는다).""")
@RestController
@RequestMapping("/api/notifications")
@RequiredArgsConstructor
public class NotificationController {

	static final String LAST_EVENT_ID = "Last-Event-ID";

	private final NotificationService service;
	private final NotificationStreamHub hub;

	@Operation(operationId = "listNotifications", summary = "내 알림 목록 (최신순)", description = """
		최초 표시·더보기용. 주기적 폴링 용도가 아니다 — 새 알림은 GET /api/notifications/stream으로 받는다.
		nextCursor는 seq 기반 불투명 값이라 더보기 중 새 알림이 앞에 붙어도 항목이 밀리거나 겹치지 않는다.
		응답 예: {"data":{"items":[{"notificationId":"ntf_7m2k9q4x1b3c","eventId":"42","type":"CHECK_REQUEST_REPLIED",
		"title":"보낸 요청에 회신이 도착했습니다","target":{"type":"REPLY","id":"rpl_..."},"parent":{"type":"CHECK_REQUEST","id":"chk_..."},
		"incidentId":"inc_...","incidentDisplayId":"EV-1024","actorUserId":"USR-CVD-01","createdAt":"2026-10-22T01:00:00Z",
		"readAt":null,"read":false}],"nextCursor":"czo0MQ"},"meta":{...}}
		오류: 400 INVALID_PAGE_SIZE(size 1~100) · 400 INVALID_CURSOR · 401 UNAUTHENTICATED.""")
	@GetMapping
	public ApiResponse<PageResponse<NotificationView>> listNotifications(@AuthenticationPrincipal AuthUser user,
			@Parameter(description = "true면 미읽음만") @RequestParam(defaultValue = "false") boolean unreadOnly,
			@RequestParam(required = false) String cursor, @RequestParam(required = false) Integer size) {
		return ApiResponse.of(service.list(user, unreadOnly, cursor, size));
	}

	@Operation(operationId = "getUnreadNotificationCount", summary = "내 미읽음 알림 수", description = """
		배지 최초 표시·재연결 후 확인용. latestEventId는 지금까지 저장된 마지막 알림 순번(없으면 "0")으로,
		이 값을 lastEventId로 SSE를 열면 그 뒤 알림을 빠짐없이 받는다. 응답 예: {"data":{"unreadCount":3,"latestEventId":"42"}}""")
	@GetMapping("/unread-count")
	public ApiResponse<UnreadCount> unreadCount(@AuthenticationPrincipal AuthUser user) {
		return ApiResponse.of(service.unreadCount(user));
	}

	@Operation(operationId = "markNotificationRead", summary = "알림 하나 읽음 처리", description = """
		반복 호출에 안전하다 — 이미 읽은 알림은 처음 readAt을 그대로 돌려주고 SSE도 다시 보내지 않는다.
		읽음은 업무·Agent를 실행하지 않는다. 처음 읽음 처리되면 같은 사용자의 다른 연결에 notification.read 이벤트를 보낸다.
		응답 예: {"data":{"notificationId":"ntf_7m2k9q4x1b3c","readAt":"2026-10-22T01:05:00Z","unreadCount":2}}
		오류: 403 CSRF 누락/불일치 · 404 NOTIFICATION_NOT_FOUND(없거나 다른 사용자의 알림 — 존재 여부를 구분하지 않는다).""")
	@PostMapping("/{notificationId}/read")
	public ApiResponse<ReadResult> markRead(@AuthenticationPrincipal AuthUser user, @PathVariable String notificationId,
			@RequestHeader(SecurityConfig.CSRF_HEADER) String csrf) {
		return ApiResponse.of(service.markRead(user, notificationId));
	}

	@Operation(operationId = "notificationStream", summary = "내 알림 SSE 구독", description = """
		로그인 사용자 본인의 알림만 보낸다(userId 파라미터 없음). 사건 SSE와 별개다. 15초마다 ": ping" 주석을 보낸다. 30분 뒤 서버가 닫으며
		EventSource가 Last-Event-ID 헤더로 자동 재연결한다.

		이벤트 (data는 JSON):
		- ready (id=기준 seq): {"latestEventId":"42","unreadCount":3} — 연결 직후 한 번.
		- notification (id=seq): {"notification":{NotificationView},"unreadCount":4} — seq 오름차순, 연결당 같은 seq를 두 번 보내지 않는다.
		- notification.read (id 없음): {"notificationId":"ntf_...","readAt":"...","unreadCount":3} — 다른 탭에서 읽음 처리됨.
		- notification.resync (id=최신 seq): {"latestEventId":"900","unreadCount":12} — 놓친 알림이 500건 초과이거나 lastEventId가
		  저장된 최신보다 크다. 목록(GET /api/notifications)을 처음부터 다시 조회한다.

		재연결 복구: Last-Event-ID 헤더(EventSource 자동) 또는 lastEventId 쿼리(새 EventSource를 만들 때)를 주면 그 다음 seq부터
		DB에서 다시 보낸 뒤 실시간 전달로 이어진다. seq는 수신자별로 커밋 순서대로 빈틈없이 증가하므로 재연결 경계에서 빠지지 않는다.
		둘 다 없으면 지금을 기준점으로 삼는다. 숫자가 아닌 값은 없는 것으로 보고 resync를 보낸다.
		권장 순서: SSE 연결(ready로 미읽음 수) → 목록 GET → 이후 SSE. 목록과 SSE가 같은 알림을 줄 수 있으니 notificationId로 중복 표시를 거른다.
		오류: 401 UNAUTHENTICATED.""")
	@GetMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
	public SseEmitter notificationStream(@AuthenticationPrincipal AuthUser user,
			@Parameter(in = ParameterIn.HEADER, name = LAST_EVENT_ID, description = "마지막으로 받은 notification event id")
			@RequestHeader(value = LAST_EVENT_ID, required = false) String lastEventIdHeader,
			@Parameter(description = "Last-Event-ID 헤더를 못 붙일 때(새 EventSource) 쓰는 같은 값. 헤더가 우선")
			@RequestParam(required = false) String lastEventId) {
		String raw = lastEventIdHeader != null && !lastEventIdHeader.isBlank() ? lastEventIdHeader : lastEventId;
		return hub.subscribe(user.userId(), parseEventId(raw));
	}

	/** 없으면 null(지금 기준), 숫자가 아니거나 음수면 Long.MAX_VALUE(→ resync) */
	private static Long parseEventId(String raw) {
		if (raw == null || raw.isBlank()) {
			return null;
		}
		try {
			long value = Long.parseLong(raw.trim());
			return value < 0 ? Long.MAX_VALUE : value;
		}
		catch (NumberFormatException e) {
			return Long.MAX_VALUE;
		}
	}
}
