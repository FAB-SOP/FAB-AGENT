package com.fabcontext.api.common;

import java.io.IOException;
import org.slf4j.MDC;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@lombok.extern.slf4j.Slf4j
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class RequestIdFilter extends OncePerRequestFilter {

	public static final String ATTRIBUTE = "fab.requestId";
	public static final String HEADER = "X-Request-Id";

	@Override
	protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
			throws ServletException, IOException {
		String requestId = Ids.next("req");
		request.setAttribute(ATTRIBUTE, requestId);
		response.setHeader(HEADER, requestId);
		String previous=MDC.get("requestId");
		MDC.put("requestId",requestId);
		long started=System.nanoTime();
		boolean dispatched=false;
		try { chain.doFilter(request, response); dispatched=true; }
		finally {
			Object pattern=request.getAttribute(org.springframework.web.servlet.HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE);
			String route=pattern==null?"UNMAPPED":pattern.toString();
			long durationMs=(System.nanoTime()-started)/1_000_000;
			// 조회·SSE 연결은 DEBUG, 상태를 변경하는 정상 요청은 INFO. 쿼리·본문·헤더는 기록하지 않는다.
			if(dispatched && !request.isAsyncStarted() && response.getStatus()<400 && java.util.Set.of("POST","PUT","PATCH","DELETE").contains(request.getMethod()))
				log.info("event=http.request method={} route={} status={} durationMs={}",request.getMethod(),route,response.getStatus(),durationMs);
			else log.debug("event=http.request method={} route={} status={} durationMs={} async={}",request.getMethod(),route,response.getStatus(),durationMs,request.isAsyncStarted());
			if(previous==null) MDC.remove("requestId"); else MDC.put("requestId",previous);
		}
	}
}
