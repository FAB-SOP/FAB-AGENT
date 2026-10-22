package com.fabcontext.api.common;

import java.util.List;

/** 커서 기반 목록 응답 */
public record PageResponse<T>(List<T> items, String nextCursor) {
}
