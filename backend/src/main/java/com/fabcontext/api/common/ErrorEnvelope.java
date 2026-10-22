package com.fabcontext.api.common;

import java.util.Map;

/** 공통 오류 envelope: { error: { code, message, details }, meta } */
public record ErrorEnvelope(ErrorBody error, Meta meta) {

	public static ErrorEnvelope of(String code, String message, Map<String, Object> details) {
		return new ErrorEnvelope(new ErrorBody(code, message, details == null ? Map.of() : details), Meta.current());
	}

	public record ErrorBody(String code, String message, Map<String, Object> details) {
	}
}
