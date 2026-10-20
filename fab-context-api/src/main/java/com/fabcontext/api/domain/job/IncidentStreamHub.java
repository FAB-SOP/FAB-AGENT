package com.fabcontext.api.domain.job;

import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

import com.fabcontext.api.common.AfterCommit;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * 사건별 SSE 구독 허브. 전역 대시보드 스트림은 계약에 없으므로 사건 단위로만 발행한다.
 * 연결이 끊기면 클라이언트는 getJob으로 상태를 복구한다.
 */
@Slf4j
@Component
public class IncidentStreamHub {

	private static final long TIMEOUT_MS = Duration.ofMinutes(30).toMillis();

	private final Map<String, Set<SseEmitter>> emitters = new ConcurrentHashMap<>();
	private final AtomicLong sequence = new AtomicLong();

	public SseEmitter subscribe(String incidentId, Object snapshot) {
		SseEmitter emitter = new SseEmitter(TIMEOUT_MS);
		Set<SseEmitter> set = emitters.computeIfAbsent(incidentId, k -> ConcurrentHashMap.newKeySet());
		set.add(emitter);
		Runnable remove = () -> set.remove(emitter);
		emitter.onCompletion(remove);
		emitter.onTimeout(remove);
		emitter.onError(e -> remove.run());
		send(incidentId, emitter, "snapshot", snapshot);
		return emitter;
	}

	/** 커밋 이후 구독자에게 발행 */
	public void publish(String incidentId, String type, Object data) {
		AfterCommit.run(() -> {
			Set<SseEmitter> set = emitters.get(incidentId);
			if (set == null) {
				return;
			}
			for (SseEmitter emitter : set) {
				send(incidentId, emitter, type, data);
			}
		});
	}

	public int subscriberCount(String incidentId) {
		Set<SseEmitter> set = emitters.get(incidentId);
		return set == null ? 0 : set.size();
	}

	@Scheduled(fixedRate = 15_000)
	void heartbeat() {
		emitters.forEach((incidentId, set) -> set.forEach(emitter -> {
			try {
				emitter.send(SseEmitter.event().comment("ping"));
			}
			catch (IOException | IllegalStateException e) {
				set.remove(emitter);
			}
		}));
	}

	private void send(String incidentId, SseEmitter emitter, String type, Object data) {
		Map<String, Object> payload = new LinkedHashMap<>();
		payload.put("type", type);
		payload.put("incidentId", incidentId);
		payload.put("at", Instant.now());
		payload.put("data", data);
		try {
			emitter.send(SseEmitter.event()
				.id(String.valueOf(sequence.incrementAndGet()))
				.name(type)
				.data(payload, MediaType.APPLICATION_JSON));
		}
		catch (IOException | IllegalStateException e) {
			log.debug("SSE 전송 실패 incident={} type={}", incidentId, type);
			Set<SseEmitter> set = emitters.get(incidentId);
			if (set != null) {
				set.remove(emitter);
			}
		}
	}
}
