package com.fabcontext.api.common;

import java.util.Map;

import lombok.Getter;
import org.springframework.http.HttpStatus;

@Getter
public class ApiException extends RuntimeException {

	private final HttpStatus status;
	private final String code;
	private final Map<String, Object> details;

	public ApiException(HttpStatus status, String code, String message, Map<String, Object> details) {
		super(message);
		this.status = status;
		this.code = code;
		this.details = details == null ? Map.of() : details;
	}

	public static ApiException badRequest(String code, String message) {
		return new ApiException(HttpStatus.BAD_REQUEST, code, message, null);
	}

	public static ApiException unauthorized(String message) {
		return new ApiException(HttpStatus.UNAUTHORIZED, "UNAUTHENTICATED", message, null);
	}

	public static ApiException forbidden(String code, String message) {
		return new ApiException(HttpStatus.FORBIDDEN, code, message, null);
	}

	public static ApiException notFound(String resource, String id) {
		return new ApiException(HttpStatus.NOT_FOUND, resource.toUpperCase() + "_NOT_FOUND",
			resource + "을(를) 찾을 수 없습니다: " + id, Map.of("id", id));
	}

	public static ApiException conflict(String code, String message, Map<String, Object> details) {
		return new ApiException(HttpStatus.CONFLICT, code, message, details);
	}

	public static ApiException unprocessable(String code, String message) {
		return new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, code, message, null);
	}

	/** 낙관적 버전 검증 실패: 최신 버전을 재조회한 뒤 다시 판단하도록 409를 돌려준다. */
	public static ApiException versionConflict(String resource, long expected, long current) {
		return conflict("VERSION_CONFLICT", resource + "이(가) 다른 요청으로 변경되었습니다. 최신 내용을 다시 조회하세요.",
			Map.of("resource", resource, "expectedVersion", expected, "currentVersion", current));
	}

	public static void checkVersion(String resource, Long expected, long current) {
		if (expected == null) {
			throw unprocessable("EXPECTED_VERSION_REQUIRED", resource + " 버전(expectedVersion)이 필요합니다.");
		}
		if (expected != current) {
			throw versionConflict(resource, expected, current);
		}
	}
}
