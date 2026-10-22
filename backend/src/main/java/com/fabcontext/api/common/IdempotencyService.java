package com.fabcontext.api.common;

import java.net.URI;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.JsonNode;

/**
 * Idempotency-Key 처리. 같은 키 + 같은 본문이면 최초 응답을 그대로 재생하고,
 * 같은 키에 다른 본문이 오면 422로 거절한다. 실패 응답(4xx/5xx)은 저장하지 않는다.
 */
@Service
@RequiredArgsConstructor
public class IdempotencyService {

	public static final String HEADER = "Idempotency-Key";
	public static final String REPLAYED_HEADER = "Idempotent-Replayed";

	private final IdempotencyRecordRepository repository;
	private final Map<String, Object> locks = new ConcurrentHashMap<>();

	public ResponseEntity<?> execute(String principal, String operation, String key, Object request,
			Supplier<ResponseEntity<?>> action) {
		validateKey(key);
		String id = principal + ":" + operation + ":" + key;
		String requestHash = Hashing.canonicalSha256(request == null ? Map.of() : request);
		Object lock = locks.computeIfAbsent(id, k -> new Object());
		synchronized (lock) {
			try {
				var existing = repository.findById(id);
				if (existing.isPresent()) {
					return replay(existing.get(), requestHash);
				}
				ResponseEntity<?> response = action.get();
				if (response.getStatusCode().is2xxSuccessful()) {
					URI location = response.getHeaders().getLocation();
					repository.save(new IdempotencyRecord(id, requestHash, response.getStatusCode().value(),
						response.getBody() == null ? null : Json.write(response.getBody()),
						location == null ? null : location.toString(), Instant.now()));
				}
				return response;
			}
			finally {
				locks.remove(id);
			}
		}
	}

	private ResponseEntity<?> replay(IdempotencyRecord record, String requestHash) {
		if (!record.getRequestHash().equals(requestHash)) {
			throw ApiException.unprocessable("IDEMPOTENCY_KEY_REUSED",
				"같은 Idempotency-Key가 다른 요청 본문으로 재사용되었습니다.");
		}
		ResponseEntity.BodyBuilder builder = ResponseEntity.status(HttpStatus.valueOf(record.getStatusCode()))
			.header(REPLAYED_HEADER, "true");
		if (record.getLocation() != null) {
			builder.location(URI.create(record.getLocation()));
		}
		if (record.getResponseBody() == null) {
			return builder.build();
		}
		return builder.body(Json.read(record.getResponseBody(), new TypeReference<JsonNode>() {
		}));
	}

	private void validateKey(String key) {
		if (key == null || key.isBlank()) {
			throw ApiException.badRequest("MISSING_HEADER", HEADER + " 헤더가 필요합니다.");
		}
		if (key.length() < 8 || key.length() > 128) {
			throw ApiException.badRequest("INVALID_IDEMPOTENCY_KEY", HEADER + "는 8~128자여야 합니다.");
		}
	}
}
