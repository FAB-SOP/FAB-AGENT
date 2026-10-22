package com.fabcontext.api.internal;

import java.util.Map;

import org.springframework.http.HttpStatus;

/**
 * 내부 API 오류 — 업무 MCP가 모델이 읽는 문장 「[CODE] 메시지 힌트」로 바꾼다.
 * 응답: {"error": {code, message, hint, retryable}, "meta": ...} (fab-agent backend 대역과 같은 모양)
 */
public class InternalError extends RuntimeException {

	static final Map<String, HttpStatus> STATUS = Map.of("NOT_FOUND", HttpStatus.NOT_FOUND, "INVALID_ARGUMENT",
			HttpStatus.BAD_REQUEST, "FORBIDDEN", HttpStatus.FORBIDDEN, "UNAUTHORIZED", HttpStatus.UNAUTHORIZED,
			"APPROVAL_REQUIRED", HttpStatus.CONFLICT, "CONFLICT", HttpStatus.CONFLICT, "APPROVAL_EXPIRED",
			HttpStatus.CONFLICT, "UNAVAILABLE", HttpStatus.SERVICE_UNAVAILABLE);

	private final String code;

	private final String hint;

	private final boolean retryable;

	public InternalError(String code, String message, String hint) {
		this(code, message, hint, false);
	}

	public InternalError(String code, String message, String hint, boolean retryable) {
		super(message);
		this.code = code;
		this.hint = hint == null ? "" : hint;
		this.retryable = retryable;
	}

	public static InternalError notFound(String message, String hint) {
		return new InternalError("NOT_FOUND", message, hint);
	}

	public static InternalError invalid(String message, String hint) {
		return new InternalError("INVALID_ARGUMENT", message, hint);
	}

	String code() {
		return code;
	}

	String hint() {
		return hint;
	}

	boolean retryable() {
		return retryable;
	}

	HttpStatus status() {
		return STATUS.getOrDefault(code, HttpStatus.UNPROCESSABLE_ENTITY);
	}
}
