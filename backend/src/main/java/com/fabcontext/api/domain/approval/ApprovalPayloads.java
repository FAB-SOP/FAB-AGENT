package com.fabcontext.api.domain.approval;

import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.Map;

import com.fabcontext.api.common.ApiException;

/** 실행기들이 같이 쓰는 payload 검사 */
public final class ApprovalPayloads {

	private ApprovalPayloads() {
	}

	public static String requireString(Map<String, Object> raw, String key) {
		Object value = raw.get(key);
		if (value == null || value.toString().isBlank()) {
			throw ApiException.unprocessable("INVALID_PAYLOAD", "payload." + key + "이(가) 필요합니다.");
		}
		return value.toString();
	}

	public static Instant parseInstant(Object value, String key) {
		if (value == null) {
			throw ApiException.unprocessable("INVALID_PAYLOAD", "payload." + key + "이(가) 필요합니다.");
		}
		try {
			return Instant.parse(value.toString());
		}
		catch (DateTimeParseException e) {
			throw ApiException.unprocessable("INVALID_PAYLOAD", "payload." + key + "는 ISO-8601 시각이어야 합니다.");
		}
	}
}
