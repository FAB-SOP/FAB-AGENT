package com.fabcontext.api.internal;

import java.util.LinkedHashMap;
import java.util.Map;

import com.fabcontext.api.common.ApiException;
import com.fabcontext.api.common.Meta;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/** 내부 API 전용 오류 변환 — 공통 Service의 ApiException도 같은 모양으로 바꾼다. */
@Order(Ordered.HIGHEST_PRECEDENCE)
@RestControllerAdvice(assignableTypes = InternalApi.class)
class InternalAdvice {

	@ExceptionHandler(InternalError.class)
	ResponseEntity<Map<String, Object>> internal(InternalError e) {
		return body(e.status(), e.code(), e.getMessage(), e.hint(), e.retryable());
	}

	@ExceptionHandler(ApiException.class)
	ResponseEntity<Map<String, Object>> api(ApiException e) {
		if (!e.getDetails().isEmpty() && e.getDetails().containsKey("errors")) {
			Map<String, Object> error = new LinkedHashMap<>();
			error.put("code", e.getCode()); error.put("message", e.getMessage());
			error.put("details", e.getDetails()); error.put("hint", ""); error.put("retryable", false);
			return ResponseEntity.status(e.getStatus()).body(Map.of("error", error, "meta", Meta.current()));
		}
		String code = switch (e.getStatus()) {
			case NOT_FOUND -> "NOT_FOUND";
			case FORBIDDEN -> "FORBIDDEN";
			case BAD_REQUEST, UNPROCESSABLE_ENTITY -> "INVALID_ARGUMENT";
			case CONFLICT -> e.getCode().startsWith("APPROVAL_") ? e.getCode() : "CONFLICT";
			case SERVICE_UNAVAILABLE -> "UNAVAILABLE";
			default -> e.getCode();
		};
		String hint = e.getCode().equals(code) ? "" : "(" + e.getCode() + ")";
		HttpStatus status = InternalError.STATUS.getOrDefault(code, e.getStatus() instanceof HttpStatus s ? s
				: HttpStatus.UNPROCESSABLE_ENTITY);
		return body(status, code, e.getMessage(), hint, code.equals("UNAVAILABLE"));
	}

	@ExceptionHandler({MissingServletRequestParameterException.class, HttpMessageNotReadableException.class,
			IllegalArgumentException.class, ClassCastException.class, NullPointerException.class})
	ResponseEntity<Map<String, Object>> badInput(Exception e) {
		return body(HttpStatus.BAD_REQUEST, "INVALID_ARGUMENT", "요청 값이 올바르지 않습니다: " + e.getMessage(), "", false);
	}

	private static ResponseEntity<Map<String, Object>> body(HttpStatus status, String code, String message, String hint,
			boolean retryable) {
		Map<String, Object> error = new LinkedHashMap<>();
		error.put("code", code);
		error.put("message", message);
		error.put("hint", hint);
		error.put("retryable", retryable);
		return ResponseEntity.status(status).body(Map.of("error", error, "meta", Meta.current()));
	}
}
