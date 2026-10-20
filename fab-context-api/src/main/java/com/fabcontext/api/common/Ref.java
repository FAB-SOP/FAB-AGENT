package com.fabcontext.api.common;

/** 다른 리소스를 가리키는 참조 (waitingRef, resultRef, executionRef 등) */
public record Ref(String type, String id) {

	public static Ref of(String type, String id) {
		return type == null || id == null ? null : new Ref(type, id);
	}
}
