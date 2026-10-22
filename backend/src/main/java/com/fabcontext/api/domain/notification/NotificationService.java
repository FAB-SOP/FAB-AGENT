package com.fabcontext.api.domain.notification;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

import com.fabcontext.api.auth.AuthUser;
import com.fabcontext.api.auth.UserAccount;
import com.fabcontext.api.auth.UserAccountRepository;
import com.fabcontext.api.common.AfterCommit;
import com.fabcontext.api.common.ApiException;
import com.fabcontext.api.common.Cursors;
import com.fabcontext.api.common.Ids;
import com.fabcontext.api.common.PageResponse;
import com.fabcontext.api.domain.notification.NotificationDtos.NotificationView;
import com.fabcontext.api.domain.notification.NotificationDtos.ReadResult;
import com.fabcontext.api.domain.notification.NotificationDtos.UnreadCount;
import com.fabcontext.api.domain.notification.NotificationRepositories.NotificationCursorRepository;
import com.fabcontext.api.domain.notification.NotificationRepositories.NotificationRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 사용자 알림 저장 · 조회 · 읽음. 저장은 업무 트랜잭션의 커밋 직전에 수신자 ID 순서로 하고(같은 트랜잭션 — 롤백되면 알림도 없다),
 * 사용자 SSE 전달은 커밋 후에 한다. 연결이 없어도 DB에 남는다. 읽음은 알림 표시 상태일 뿐 업무 · Agent를 움직이지 않는다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class NotificationService {

	private final NotificationRepository notifications;
	private final NotificationCursorRepository cursors;
	private final UserAccountRepository users;
	private final NotificationStreamHub hub;
	private final TransactionTemplate tx;
	private final Clock clock;

	/**
	 * 업무 트랜잭션 안에서 부른다. 수신자는 호출한 쪽이 리소스 관계(담당자 · 요청자 · 승인자 등)로 정한 사용자이고,
	 * 등록되지 않은 ID(agent · svc_*)와 행위자 본인은 뺀다. 같은 sourceKey는 수신자당 한 번만 저장한다.
	 */
	public void publish(NotificationDraft draft, Collection<String> recipients) {
		Set<String> targets = new TreeSet<>();
		for (String r : recipients) {
			if (r != null && !r.isBlank() && !r.equals(draft.actorUserId())) {
				targets.add(r);
			}
		}
		if (targets.isEmpty()) {
			return;
		}
		if (TransactionSynchronizationManager.isActualTransactionActive()
				&& TransactionSynchronizationManager.isSynchronizationActive()) {
			pending().add(draft, targets);
			return;
		}
		Set<String> stored = tx.execute(status -> store(List.of(new Entry(draft, targets))));
		hub.deliver(stored);
	}

	@Transactional(readOnly = true)
	public PageResponse<NotificationView> list(AuthUser user, boolean unreadOnly, String cursor, Integer size) {
		int limit = size == null ? Cursors.DEFAULT_SIZE : size;
		if (limit < 1 || limit > Cursors.MAX_SIZE) {
			throw ApiException.badRequest("INVALID_PAGE_SIZE", "size는 1~" + Cursors.MAX_SIZE + " 사이여야 합니다.");
		}
		List<Notification> rows = notifications.pageDesc(user.userId(), decodeCursor(cursor), unreadOnly,
			PageRequest.of(0, limit + 1));
		List<NotificationView> items = rows.stream().limit(limit).map(NotificationView::from).toList();
		String next = rows.size() > limit ? encodeCursor(rows.get(limit - 1).getSeq()) : null;
		return new PageResponse<>(items, next);
	}

	@Transactional(readOnly = true)
	public UnreadCount unreadCount(AuthUser user) {
		// 기준점을 먼저 읽는다 — 그 뒤에 커밋된 알림은 이 값을 Last-Event-ID로 연결하면 SSE로 다시 온다
		long latest = hub.latestSeq(user.userId());
		return new UnreadCount(notifications.countByRecipientUserIdAndReadAtIsNull(user.userId()), String.valueOf(latest));
	}

	/** 반복 호출 안전 — 이미 읽은 알림은 처음 readAt을 그대로 돌려준다. 다른 사용자의 알림은 존재 여부도 알리지 않는다(404). */
	@Transactional
	public ReadResult markRead(AuthUser user, String notificationId) {
		notifications.findByNotificationIdAndRecipientUserId(notificationId, user.userId())
			.orElseThrow(() -> ApiException.notFound("notification", notificationId));
		boolean changed = notifications.markRead(notificationId, user.userId(), Instant.now(clock)) > 0;
		Notification read = notifications.findById(notificationId).orElseThrow();
		long unread = notifications.countByRecipientUserIdAndReadAtIsNull(user.userId());
		if (changed) {
			String requestId=org.slf4j.MDC.get("requestId");
			AfterCommit.run(() -> {
				log.info("event=notification.read requestId={} notificationId={} userId={}",requestId,notificationId,user.userId());
				hub.readChanged(user.userId(), notificationId, read.getReadAt());
			});
		}
		return new ReadResult(notificationId, read.getReadAt(), unread);
	}

	/** 수신자 ID 순서로 순번 행을 잠그고 저장한다 — 여러 트랜잭션이 같은 순서로 잠가 서로 기다리다 막히지 않는다 */
	private Set<String> store(List<Entry> entries) {
		Map<String, List<NotificationDraft>> byRecipient = new TreeMap<>();
		for (Entry e : entries) {
			for (String r : e.recipients()) {
				byRecipient.computeIfAbsent(r, k -> new ArrayList<>()).add(e.draft());
			}
		}
		Set<String> known = new HashSet<>();
		users.findAllById(byRecipient.keySet()).forEach((UserAccount u) -> known.add(u.getUserId()));
		Instant now = Instant.now(clock);
		Set<String> stored = new TreeSet<>();
		byRecipient.forEach((recipient, drafts) -> {
			if (!known.contains(recipient)) {
				log.debug("등록되지 않은 수신자 알림 생략 recipient={}", recipient);
				return;
			}
			cursors.ensure(recipient);
			NotificationCursor cursor = cursors.lock(recipient).orElseThrow();
			Set<String> seen = new HashSet<>();
			for (NotificationDraft d : drafts) {
				if (!seen.add(d.sourceKey()) || notifications.existsByRecipientUserIdAndSourceKey(recipient, d.sourceKey())) {
					log.debug("event=notification.duplicate.skipped userId={} type={}",recipient,d.type());
					continue;
				}
				Notification saved=notifications.save(new Notification(Ids.next("ntf"), recipient, cursor.next(), d, now));
				String requestId=org.slf4j.MDC.get("requestId");
				AfterCommit.run(()->log.info("event=notification.stored requestId={} notificationId={} incidentId={} userId={} type={} eventId={}",
					requestId,saved.getNotificationId(),saved.getIncidentId(),recipient,saved.getType(),saved.getSeq()));
				stored.add(recipient);
			}
		});
		return stored;
	}

	private Pending pending() {
		for (TransactionSynchronization s : TransactionSynchronizationManager.getSynchronizations()) {
			if (s instanceof Pending p && p.owner() == this) {
				return p;
			}
		}
		Pending p = new Pending();
		TransactionSynchronizationManager.registerSynchronization(p);
		return p;
	}

	private record Entry(NotificationDraft draft, Set<String> recipients) {
	}

	/** 한 트랜잭션에서 쌓인 알림 — 커밋 직전 한 번에 저장, 커밋 후 전달 */
	private final class Pending implements TransactionSynchronization {

		private final List<Entry> entries = new ArrayList<>();
		private final Set<String> stored = new TreeSet<>();
		private boolean flushed;

		NotificationService owner() {
			return NotificationService.this;
		}

		void add(NotificationDraft draft, Set<String> recipients) {
			if (flushed) {
				// 다른 커밋 직전 작업이 늦게 부른 경우 — 아직 같은 트랜잭션이므로 바로 저장한다
				stored.addAll(store(List.of(new Entry(draft, recipients))));
				return;
			}
			entries.add(new Entry(draft, recipients));
		}

		@Override
		public void beforeCommit(boolean readOnly) {
			flushed = true;
			try { stored.addAll(store(entries)); }
			catch(RuntimeException e) {
				log.error("event=notification.store.failed requestId={} errorType={} transaction=rollback",org.slf4j.MDC.get("requestId"),e.getClass().getSimpleName());
				throw e;
			}
		}

		@Override
		public void afterCommit() {
			hub.deliver(stored);
		}
	}

	private static long decodeCursor(String cursor) {
		if (cursor == null || cursor.isBlank()) {
			return Long.MAX_VALUE;
		}
		try {
			String raw = new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8);
			if (!raw.startsWith("s:")) {
				throw new IllegalArgumentException();
			}
			long seq = Long.parseLong(raw.substring(2));
			if (seq < 1) {
				throw new IllegalArgumentException();
			}
			return seq;
		}
		catch (IllegalArgumentException e) {
			throw ApiException.badRequest("INVALID_CURSOR", "cursor 값이 올바르지 않습니다.");
		}
	}

	private static String encodeCursor(long seq) {
		return Base64.getUrlEncoder().withoutPadding().encodeToString(("s:" + seq).getBytes(StandardCharsets.UTF_8));
	}
}
