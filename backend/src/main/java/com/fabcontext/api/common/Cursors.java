package com.fabcontext.api.common;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.function.Function;

/** offset을 감싼 불투명 커서. 데모 규모의 메모리 페이지네이션에 사용한다. */
public final class Cursors {

	public static final int DEFAULT_SIZE = 20;
	public static final int MAX_SIZE = 100;

	private Cursors() {
	}

	public static <E, D> PageResponse<D> page(List<E> all, String cursor, Integer size, Function<E, D> mapper) {
		int offset = decode(cursor);
		int limit = size == null ? DEFAULT_SIZE : size;
		if (limit < 1 || limit > MAX_SIZE) {
			throw ApiException.badRequest("INVALID_PAGE_SIZE", "size는 1~" + MAX_SIZE + " 사이여야 합니다.");
		}
		int end = Math.min(all.size(), offset + limit);
		List<D> items = offset >= all.size() ? List.of() : all.subList(offset, end).stream().map(mapper).toList();
		String next = end < all.size() ? encode(end) : null;
		return new PageResponse<>(items, next);
	}

	static String encode(int offset) {
		return Base64.getUrlEncoder().withoutPadding()
			.encodeToString(("o:" + offset).getBytes(StandardCharsets.UTF_8));
	}

	static int decode(String cursor) {
		if (cursor == null || cursor.isBlank()) {
			return 0;
		}
		try {
			String raw = new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8);
			if (!raw.startsWith("o:")) {
				throw new IllegalArgumentException();
			}
			int offset = Integer.parseInt(raw.substring(2));
			if (offset < 0) {
				throw new IllegalArgumentException();
			}
			return offset;
		}
		catch (IllegalArgumentException e) {
			throw ApiException.badRequest("INVALID_CURSOR", "cursor 값이 올바르지 않습니다.");
		}
	}
}
