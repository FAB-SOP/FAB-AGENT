package com.fabcontext.api.common;

import java.time.Instant;

import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;

/** 모든 응답에 붙는 meta. requestId는 불투명 식별자다. */
public record Meta(String requestId, Instant at) {

	public static Meta current() {
		String requestId = null;
		RequestAttributes attrs = RequestContextHolder.getRequestAttributes();
		if (attrs != null) {
			requestId = (String) attrs.getAttribute(RequestIdFilter.ATTRIBUTE, RequestAttributes.SCOPE_REQUEST);
		}
		return new Meta(requestId != null ? requestId : Ids.next("req"), Instant.now());
	}
}
