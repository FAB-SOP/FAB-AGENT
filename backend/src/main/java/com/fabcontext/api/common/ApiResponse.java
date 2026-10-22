package com.fabcontext.api.common;

/** 공통 성공 응답 envelope: { data, meta } */
public record ApiResponse<T>(T data, Meta meta) {

	public static <T> ApiResponse<T> of(T data) {
		return new ApiResponse<>(data, Meta.current());
	}
}
