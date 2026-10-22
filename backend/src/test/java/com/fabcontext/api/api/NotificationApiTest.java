package com.fabcontext.api.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;

import com.fabcontext.api.domain.notification.NotificationDraft;
import com.fabcontext.api.domain.notification.NotificationService;
import com.fabcontext.api.domain.notification.NotificationStreamHub;
import com.fabcontext.api.domain.notification.NotificationType;
import com.fabcontext.api.support.ApiTestSupport;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.support.TransactionTemplate;

/** 사용자 알림 — 저장/롤백, 커밋 후 전달, 사용자 격리, 중복, 읽음, 재연결 복구 */
class NotificationApiTest extends ApiTestSupport {

	static final String CMP_USER = "USR-CMP-01";
	static final String CVD_USER = "USR-CVD-01";

	@Autowired
	NotificationService notifications;

	@Autowired
	NotificationStreamHub hub;

	@Autowired
	TransactionTemplate tx;

	@Autowired
	JdbcTemplate jdbc;

	record SseEvent(String id, String name, String data) {
	}

	@Test
	void storedNotificationIsListedOnlyForRecipientAndOtherUsersCannotReadIt() throws Exception {
		Session cvd = cvd();
		Session cmp = cmp();
		String source = publishCommitted(CVD_USER);

		String id = findBySource(cvd, source);
		assertThat(id).isNotNull();
		getAs(cvd, "/api/notifications?size=5")
			.andExpect(jsonPath("$.data.items[0].type").value("CHECK_REQUEST_RECEIVED"))
			.andExpect(jsonPath("$.data.items[0].target.type").value("CHECK_REQUEST"))
			.andExpect(jsonPath("$.data.items[0].read").value(false));
		assertThat(findBySource(cmp, source)).isNull();
		// 다른 사용자 알림은 존재 여부도 드러내지 않는다
		postAs(cmp, "/api/notifications/{id}/read", "", id).andExpect(status().isNotFound())
			.andExpect(jsonPath("$.error.code").value("NOTIFICATION_NOT_FOUND"));
		assertThat(json(getAs(cvd, "/api/notifications?size=100"), "$.data.items[?(@.notificationId=='" + id
			+ "')].read").toString()).isEqualTo("[false]");
	}

	@Test
	void rolledBackTransactionStoresAndSendsNothing() throws Exception {
		Session cvd = cvd();
		MvcResult stream = openStream(cvd, null);
		String source = "rollback-" + UUID.randomUUID();
		tx.executeWithoutResult(status -> {
			notifications.publish(draft(source), List.of(CVD_USER));
			status.setRollbackOnly();
		});
		try {
			tx.executeWithoutResult(status -> {
				notifications.publish(draft(source), List.of(CVD_USER));
				throw new IllegalStateException("업무 실패");
			});
		}
		catch (IllegalStateException expected) {
			// 업무 실패로 롤백
		}
		assertThat(rows(CVD_USER, source)).isZero();
		// 같은 연결에 이후 커밋된 알림이 도착할 때까지 기다린 뒤, 롤백 건이 없었는지 본다
		String marker = publishCommitted(CVD_USER);
		List<SseEvent> events = await(stream, e -> e.data().contains(marker));
		assertThat(events).noneMatch(e -> e.data().contains(source));
	}

	@Test
	void deliveryHappensOnlyAfterCommitAndOfflineNotificationsSurvive() throws Exception {
		Session cvd = cvd();
		MvcResult stream = openStream(cvd, null);
		String source = "after-commit-" + UUID.randomUUID();
		tx.executeWithoutResult(status -> {
			notifications.publish(draft(source), List.of(CVD_USER));
			sleep(150);
			assertThat(text(stream)).doesNotContain(source);
			assertThat(rowsOutsideTx(CVD_USER, source)).isZero();
		});
		await(stream, e -> "notification".equals(e.name()) && e.data().contains(source));

		// 연결이 없을 때 저장된 알림 — 다음 연결의 Last-Event-ID 복구로 받는다
		Session cmp = cmp();
		long before = Long.parseLong(json(getAs(cmp, "/api/notifications/unread-count"), "$.data.latestEventId"));
		String offline = publishCommitted(CMP_USER);
		assertThat(findBySource(cmp, offline)).isNotNull();
		MvcResult later = openStream(cmp, before);
		await(later, e -> "notification".equals(e.name()) && e.data().contains(offline));
	}

	@Test
	void sameSourceEventIsStoredOncePerRecipientAndActorAndUnknownUsersAreSkipped() throws Exception {
		String source = "dup-" + UUID.randomUUID();
		tx.executeWithoutResult(status -> {
			notifications.publish(draft(source), List.of(CVD_USER, CVD_USER, "agent", "svc_simulator"));
			notifications.publish(draft(source), List.of(CVD_USER));
		});
		tx.executeWithoutResult(status -> notifications.publish(draft(source), List.of(CVD_USER)));
		assertThat(rows(CVD_USER, source)).isEqualTo(1);
		assertThat(rows("agent", source)).isZero();

		// 행위자 본인은 받지 않는다
		String own = "own-" + UUID.randomUUID();
		tx.executeWithoutResult(status -> notifications.publish(new NotificationDraft(
			NotificationType.CHECK_REQUEST_RECEIVED, own, own, null, null, null, CVD_USER), List.of(CVD_USER, CMP_USER)));
		assertThat(rows(CVD_USER, own)).isZero();
		assertThat(rows(CMP_USER, own)).isEqualTo(1);
	}

	@Test
	void repeatedReadIsSafeAndUnreadCountDropsOnce() throws Exception {
		Session cvd = cvd();
		String id = findBySource(cvd, publishCommitted(CVD_USER));
		long unread = number(getAs(cvd, "/api/notifications/unread-count"), "$.data.unreadCount");

		String first = json(postAs(cvd, "/api/notifications/{id}/read", "", id).andExpect(status().isOk()),
			"$.data.readAt");
		var again = postAs(cvd, "/api/notifications/{id}/read", "", id).andExpect(status().isOk());
		assertThat(json(again, "$.data.readAt").toString()).isEqualTo(first);
		assertThat(number(again, "$.data.unreadCount")).isEqualTo(unread - 1);
		assertThat(number(getAs(cvd, "/api/notifications/unread-count"), "$.data.unreadCount")).isEqualTo(unread - 1);
		assertThat(json(getAs(cvd, "/api/notifications?unreadOnly=true&size=100"), "$.data.items[*].notificationId")
			.toString()).doesNotContain(id);
		// CSRF 없는 읽음은 거부
		mvc.perform(post("/api/notifications/{id}/read", id).session(cvd.http())).andExpect(status().isForbidden());
	}

	@Test
	void readOnOneTabIsPushedToOtherConnectionsOfSameUserOnly() throws Exception {
		Session cvd = cvd();
		Session cmp = cmp();
		String id = findBySource(cvd, publishCommitted(CVD_USER));
		MvcResult otherTab = openStream(cvd, null);
		MvcResult otherUser = openStream(cmp, null);
		postAs(cvd, "/api/notifications/{id}/read", "", id).andExpect(status().isOk());
		await(otherTab, e -> "notification.read".equals(e.name()) && e.data().contains(id));
		String marker = publishCommitted(CMP_USER);
		List<SseEvent> cmpEvents = await(otherUser, e -> e.data().contains(marker));
		assertThat(cmpEvents).noneMatch(e -> e.data().contains(id));
	}

	@Test
	void streamIsIsolatedPerLoggedInUser() throws Exception {
		Session cvd = cvd();
		Session cmp = cmp();
		MvcResult cvdStream = openStream(cvd, null);
		MvcResult cmpStream = openStream(cmp, null);
		String forCvd = publishCommitted(CVD_USER);
		String forCmp = publishCommitted(CMP_USER);
		await(cvdStream, e -> e.data().contains(forCvd));
		List<SseEvent> cmpEvents = await(cmpStream, e -> e.data().contains(forCmp));
		assertThat(cmpEvents).noneMatch(e -> e.data().contains(forCvd));
		assertThat(events(cvdStream)).noneMatch(e -> e.data().contains(forCmp));
		// userId를 받지 않는다 — 쿼리로 넣어도 무시되고 세션 사용자 기준
		MvcResult spoof = mvc.perform(get("/api/notifications/stream?userId=" + CVD_USER).session(cmp.http()))
			.andExpect(request().asyncStarted()).andReturn();
		String another = publishCommitted(CVD_USER);
		await(cvdStream, e -> e.data().contains(another));
		assertThat(events(spoof)).noneMatch(e -> e.data().contains(another));
		mvc.perform(get("/api/notifications/stream")).andExpect(status().isUnauthorized());
		mvc.perform(get("/api/notifications")).andExpect(status().isUnauthorized());
	}

	@Test
	void reconnectReplaysOnlyMissedNotificationsInOrder() throws Exception {
		Session cvd = cvd();
		String a = publishCommitted(CVD_USER);
		String aEvent = eventIdOf(cvd, a);
		String b = publishCommitted(CVD_USER);
		String c = publishCommitted(CVD_USER);

		MvcResult stream = mvc.perform(get("/api/notifications/stream").session(cvd.http())
			.header("Last-Event-ID", aEvent)).andExpect(request().asyncStarted()).andReturn();
		List<SseEvent> got = await(stream, e -> e.data().contains(c));
		List<SseEvent> notes = got.stream().filter(e -> "notification".equals(e.name())).toList();
		assertThat(notes).noneMatch(e -> e.data().contains(a));
		assertThat(notes.get(0).data()).contains(b);
		assertThat(notes.get(1).data()).contains(c);
		assertThat(Long.parseLong(notes.get(1).id())).isEqualTo(Long.parseLong(notes.get(0).id()) + 1);
		String d = publishCommitted(CVD_USER);
		List<SseEvent> after = await(stream, e -> e.data().contains(d));
		assertThat(after.stream().filter(e -> e.data().contains(c)).count()).isEqualTo(1);

		// 쿼리 lastEventId도 같은 의미(새 EventSource). 저장된 최신보다 큰 값은 resync
		MvcResult query = openStream(cvd, Long.parseLong(aEvent));
		await(query, e -> e.data().contains(d));
		MvcResult ahead = openStream(cvd, Long.MAX_VALUE - 1);
		await(ahead, e -> "notification.resync".equals(e.name()));
	}

	@Test
	void concurrentPublishersAndReconnectBoundaryDeliverEverySeqExactlyOnce() throws Exception {
		Session cvd = cvd();
		long base = Long.parseLong(json(getAs(cvd, "/api/notifications/unread-count"), "$.data.latestEventId"));
		MvcResult live = openStream(cvd, base);
		int total = 30;
		ExecutorService pool = Executors.newFixedThreadPool(8);
		CountDownLatch half = new CountDownLatch(total / 2);
		List<Future<?>> futures = new ArrayList<>();
		String batch = "conc-" + UUID.randomUUID();
		for (int i = 0; i < total; i++) {
			String source = batch + "-" + i;
			futures.add(pool.submit(() -> {
				tx.executeWithoutResult(s -> notifications.publish(draft(source), List.of(CVD_USER)));
				half.countDown();
			}));
		}
		half.await(10, TimeUnit.SECONDS);
		// 발행이 이어지는 중에 재연결 — 기준점 이후를 빠짐 · 중복 없이 받아야 한다
		MvcResult reconnect = openStream(cvd, base + 5);
		for (Future<?> f : futures) {
			f.get(20, TimeUnit.SECONDS);
		}
		pool.shutdown();

		assertThat(jdbc.queryForObject("select count(distinct seq) from user_notification where recipient_user_id = ? "
			+ "and source_key like ?", Long.class, CVD_USER, "%" + batch + "%")).isEqualTo(total);
		assertSeqs(awaitCount(live, batch, total), base + 1, base + total, batch);
		assertSeqs(awaitCount(reconnect, batch, total - 5), base + 6, base + total, batch);
	}

	@Test
	void listPagesWithStableCursor() throws Exception {
		Session cvd = cvd();
		for (int i = 0; i < 3; i++) {
			publishCommitted(CVD_USER);
		}
		var first = getAs(cvd, "/api/notifications?size=2").andExpect(status().isOk());
		List<String> page1 = json(first, "$.data.items[*].eventId");
		String next = json(first, "$.data.nextCursor");
		publishCommitted(CVD_USER); // 더보기 전 새 알림이 와도 다음 페이지가 밀리지 않는다
		List<String> page2 = json(getAs(cvd, "/api/notifications?size=2&cursor=" + next), "$.data.items[*].eventId");
		assertThat(Long.parseLong(page2.get(0))).isEqualTo(Long.parseLong(page1.get(1)) - 1);
		getAs(cvd, "/api/notifications?cursor=bad!").andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.error.code").value("INVALID_CURSOR"));
		getAs(cvd, "/api/notifications?size=0").andExpect(status().isBadRequest());
	}

	// --- 도움 함수 ---

	private static NotificationDraft draft(String source) {
		return new NotificationDraft(NotificationType.CHECK_REQUEST_RECEIVED, source, "chk_" + source, null, null, null,
			null);
	}

	private String publishCommitted(String userId) {
		String source = "src-" + UUID.randomUUID();
		tx.executeWithoutResult(status -> notifications.publish(draft(source), List.of(userId)));
		return source;
	}

	private long rows(String userId, String source) {
		return jdbc.queryForObject("select count(*) from user_notification where recipient_user_id = ? and source_key = ?",
			Long.class, userId, "CHECK_REQUEST_RECEIVED:" + source);
	}

	/** 다른 스레드(다른 연결)에서 본 행 수 — 커밋 전에는 보이지 않아야 한다 */
	private long rowsOutsideTx(String userId, String source) {
		try {
			return Executors.newSingleThreadExecutor().submit(() -> rows(userId, source)).get(5, TimeUnit.SECONDS);
		}
		catch (Exception e) {
			throw new IllegalStateException(e);
		}
	}

	private String findBySource(Session s, String source) throws Exception {
		List<String> ids = json(getAs(s, "/api/notifications?size=100"),
			"$.data.items[?(@.target.id=='chk_" + source + "')].notificationId");
		return ids.isEmpty() ? null : ids.get(0);
	}

	private String eventIdOf(Session s, String source) throws Exception {
		List<String> ids = json(getAs(s, "/api/notifications?size=100"),
			"$.data.items[?(@.target.id=='chk_" + source + "')].eventId");
		return ids.get(0);
	}

	private MvcResult openStream(Session s, Long lastEventId) throws Exception {
		String url = "/api/notifications/stream" + (lastEventId == null ? "" : "?lastEventId=" + lastEventId);
		MvcResult result = mvc.perform(get(url).session(s.http())).andExpect(request().asyncStarted()).andReturn();
		await(result, e -> "ready".equals(e.name()));
		return result;
	}

	/** batch 알림이 n건 이상 올 때까지 기다린 뒤 조금 더 기다려 늦은 중복이 없는지도 본다 */
	private static List<SseEvent> awaitCount(MvcResult r, String batch, int n) {
		for (int i = 0; i < 400; i++) {
			if (events(r).stream().filter(e -> e.data().contains(batch)).count() >= n) {
				sleep(200);
				return events(r);
			}
			sleep(25);
		}
		throw new AssertionError("SSE 알림 " + n + "건을 받지 못했습니다: " + text(r));
	}

	private static void assertSeqs(List<SseEvent> all, long from, long to, String batch) {
		List<Long> seqs = all.stream().filter(e -> "notification".equals(e.name()) && e.data().contains(batch))
			.map(e -> Long.parseLong(e.id())).toList();
		List<Long> expected = new ArrayList<>();
		for (long i = from; i <= to; i++) {
			expected.add(i);
		}
		assertThat(seqs).containsExactlyElementsOf(expected);
	}

	private static String text(MvcResult r) {
		try {
			return r.getResponse().getContentAsString(StandardCharsets.UTF_8);
		}
		catch (Exception e) {
			throw new IllegalStateException(e);
		}
	}

	private static List<SseEvent> events(MvcResult r) {
		List<SseEvent> out = new ArrayList<>();
		for (String frame : text(r).split("\n\n")) {
			String id = null;
			String name = null;
			StringBuilder data = new StringBuilder();
			for (String line : frame.split("\n")) {
				if (line.startsWith("id:")) {
					id = line.substring(3);
				}
				else if (line.startsWith("event:")) {
					name = line.substring(6);
				}
				else if (line.startsWith("data:")) {
					data.append(line.substring(5));
				}
			}
			if (name != null) {
				out.add(new SseEvent(id, name, data.toString()));
			}
		}
		return out;
	}

	private static List<SseEvent> await(MvcResult r, Predicate<SseEvent> until) {
		List<SseEvent> last = List.of();
		for (int i = 0; i < 200; i++) {
			last = events(r);
			if (last.stream().anyMatch(until)) {
				return last;
			}
			sleep(25);
		}
		throw new AssertionError("SSE 이벤트를 받지 못했습니다: " + text(r));
	}

	private static void sleep(long ms) {
		try {
			Thread.sleep(ms);
		}
		catch (InterruptedException e) {
			Thread.currentThread().interrupt();
		}
	}

}
