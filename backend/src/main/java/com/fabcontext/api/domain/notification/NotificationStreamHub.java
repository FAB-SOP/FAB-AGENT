package com.fabcontext.api.domain.notification;

import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

import com.fabcontext.api.domain.notification.NotificationDtos.NotificationView;
import com.fabcontext.api.domain.notification.NotificationRepositories.NotificationCursorRepository;
import com.fabcontext.api.domain.notification.NotificationRepositories.NotificationRepository;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.MediaType;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * 사용자별 알림 SSE. 연결은 로그인 사용자 ID로만 묶는다(클라이언트가 userId를 고르지 않는다).
 * <p>
 * DB가 원본이다 — 커밋 후 신호를 받으면 연결마다 "마지막으로 보낸 seq 다음"을 DB에서 읽어 보낸다. 같은 연결의 전송은 직렬화하고
 * 수신자별 seq는 커밋 순서와 같으므로, 신호가 늦거나 순서가 바뀌거나 재연결 직후 겹쳐도 빠짐 · 중복 없이 seq 순으로 간다.
 * 이벤트: ready · notification(id=seq) · notification.read · notification.resync(id=최신 seq).
 */
@Slf4j
@Component
public class NotificationStreamHub {

	private static final long TIMEOUT_MS = Duration.ofMinutes(30).toMillis();
	static final int REPLAY_PAGE = 100;
	/** 재연결 복구로 다시 보내는 최대 건수 — 넘으면 resync로 목록 재조회를 요청한다 */
	static final int REPLAY_MAX = 500;

	private final NotificationRepository notifications;
	private final NotificationCursorRepository cursors;
	private final Map<String, Set<Connection>> connections = new ConcurrentHashMap<>();
	private final ExecutorService dispatcher;

	public NotificationStreamHub(NotificationRepository notifications, NotificationCursorRepository cursors) {
		this.notifications = notifications;
		this.cursors = cursors;
		AtomicInteger n = new AtomicInteger();
		this.dispatcher = Executors.newFixedThreadPool(2, r -> {
			Thread t = new Thread(r, "fab-notify-" + n.incrementAndGet());
			t.setDaemon(true);
			return t;
		});
	}

	/**
	 * lastEventId가 있으면 그 다음 알림부터 다시 보낸다. 없으면 지금을 기준점으로 삼는다(이전 알림은 목록 GET으로 본다).
	 * 놓친 알림이 {@value #REPLAY_MAX}건을 넘거나 저장된 최신보다 큰 값(다른 DB · 손상된 값)이면 기준점을 최신으로 맞추고
	 * resync를 보낸다 — 클라이언트는 목록을 다시 조회한다(seq는 수신자별로 빈틈이 없어 차이가 곧 놓친 건수다).
	 */
	public SseEmitter subscribe(String userId, Long lastEventId) {
		SseEmitter emitter = new SseEmitter(TIMEOUT_MS);
		Connection c = new Connection(userId, emitter);
		synchronized (c) {
			long latest = latestSeq(userId);
			boolean resync = lastEventId != null && (lastEventId > latest || latest - lastEventId > REPLAY_MAX);
			c.lastSent = lastEventId == null || resync ? latest : lastEventId;
			Set<Connection> set = connections.computeIfAbsent(userId, k -> ConcurrentHashMap.newKeySet());
			set.add(c);
			Runnable remove = () -> set.remove(c);
			emitter.onCompletion(remove);
			emitter.onTimeout(remove);
			emitter.onError(e -> remove.run());
			Map<String, Object> ready = new LinkedHashMap<>();
			ready.put("latestEventId", String.valueOf(c.lastSent));
			ready.put("unreadCount", unread(userId));
			send(c, "ready", String.valueOf(c.lastSent), ready);
			if (resync) {
				sendResync(c, latest);
			}
			pump(c);
		}
		return emitter;
	}

	/** 커밋 후 신호 — 업무 요청 스레드를 붙잡지 않도록 별도 스레드에서 DB를 읽고 보낸다 */
	void deliver(Collection<String> userIds) {
		if (userIds == null || userIds.isEmpty()) {
			return;
		}
		List<String> ids = List.copyOf(userIds);
		dispatcher.execute(() -> ids.forEach(userId -> forEach(userId, c -> {
			synchronized (c) {
				pump(c);
			}
		})));
	}

	/** 다른 탭 · 기기의 배지를 맞춘다. 저장 순번이 없는 상태 이벤트라 id를 붙이지 않는다(재연결 기준점을 움직이지 않음) */
	void readChanged(String userId, String notificationId, Instant readAt) {
		dispatcher.execute(() -> forEach(userId, c -> {
			synchronized (c) {
				Map<String, Object> data = new LinkedHashMap<>();
				data.put("notificationId", notificationId);
				data.put("readAt", readAt);
				data.put("unreadCount", unread(userId));
				send(c, "notification.read", null, data);
			}
		}));
	}

	long latestSeq(String userId) {
		return cursors.findById(userId).map(NotificationCursor::getLastSeq).orElse(0L);
	}

	public int subscriberCount(String userId) {
		Set<Connection> set = connections.get(userId);
		return set == null ? 0 : set.size();
	}

	@Scheduled(fixedRate = 15_000)
	void heartbeat() {
		connections.forEach((userId, set) -> set.forEach(c -> {
			synchronized (c) {
				try {
					c.emitter.send(SseEmitter.event().comment("ping"));
				}
				catch (IOException | IllegalStateException e) {
					set.remove(c);
				}
			}
		}));
	}

	@PreDestroy
	void shutdown() {
		dispatcher.shutdownNow();
	}

	/** 호출자가 c를 잠근 상태여야 한다 */
	private void pump(Connection c) {
		while (c.open) {
			List<Notification> batch = notifications.findByRecipientUserIdAndSeqGreaterThanOrderBySeqAsc(c.userId,
				c.lastSent, PageRequest.of(0, REPLAY_PAGE));
			if (batch.isEmpty()) {
				return;
			}
			long unread = unread(c.userId);
			for (Notification n : batch) {
				Map<String, Object> data = new LinkedHashMap<>();
				data.put("notification", NotificationView.from(n));
				data.put("unreadCount", unread);
				if (!send(c, "notification", String.valueOf(n.getSeq()), data)) {
					return;
				}
				c.lastSent = n.getSeq();
			}
			if (batch.size() < REPLAY_PAGE) {
				return;
			}
		}
	}

	private void sendResync(Connection c, long latest) {
		Map<String, Object> data = new LinkedHashMap<>();
		data.put("latestEventId", String.valueOf(latest));
		data.put("unreadCount", unread(c.userId));
		if (send(c, "notification.resync", String.valueOf(latest), data)) {
			c.lastSent = latest;
		}
	}

	private long unread(String userId) {
		return notifications.countByRecipientUserIdAndReadAtIsNull(userId);
	}

	private void forEach(String userId, Consumer<Connection> action) {
		Set<Connection> set = connections.get(userId);
		if (set == null) {
			return;
		}
		for (Connection c : set) {
			try {
				action.accept(c);
			}
			catch (RuntimeException e) {
				log.warn("알림 SSE 전달 실패 user={}", userId, e);
			}
		}
	}

	private boolean send(Connection c, String type, String id, Object data) {
		try {
			SseEmitter.SseEventBuilder event = SseEmitter.event().name(type).data(data, MediaType.APPLICATION_JSON);
			if (id != null) {
				event.id(id);
			}
			c.emitter.send(event);
			return true;
		}
		catch (IOException | IllegalStateException e) {
			log.debug("알림 SSE 전송 실패 user={} type={}", c.userId, type);
			c.open = false;
			Set<Connection> set = connections.get(c.userId);
			if (set != null) {
				set.remove(c);
			}
			return false;
		}
	}

	private static final class Connection {

		private final String userId;
		private final SseEmitter emitter;
		/** 이 연결로 보낸 마지막 seq — 잠근 상태에서만 읽고 쓴다 */
		private long lastSent;
		private volatile boolean open = true;

		Connection(String userId, SseEmitter emitter) {
			this.userId = userId;
			this.emitter = emitter;
		}
	}
}
