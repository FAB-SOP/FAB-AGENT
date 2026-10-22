package com.fabcontext.api.auth;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * 서비스 토큰 인증. 세션을 만들지 않는다.
 * /api/events/** — 이벤트 시뮬레이터(ROLE_SERVICE), /internal/** — Agent · 업무 MCP(ROLE_AGENT).
 */
public class ServiceTokenFilter extends OncePerRequestFilter {

	public static final String HEADER = "X-Service-Token";

	private final byte[] expected;

	private final byte[] internal;

	public ServiceTokenFilter(String serviceToken) {
		this(serviceToken, null);
	}

	public ServiceTokenFilter(String serviceToken, String internalToken) {
		this.expected = serviceToken == null ? new byte[0] : serviceToken.getBytes(StandardCharsets.UTF_8);
		this.internal = internalToken == null ? new byte[0] : internalToken.getBytes(StandardCharsets.UTF_8);
	}

	@Override
	protected boolean shouldNotFilter(HttpServletRequest request) {
		String uri = request.getRequestURI();
		return !uri.startsWith("/api/events/") && !uri.startsWith("/internal/");
	}

	@Override
	protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
			throws ServletException, IOException {
		String token = request.getHeader(HEADER);
		String authorization = request.getHeader("Authorization");
		if (token == null && authorization != null && authorization.startsWith("Bearer ")) {
			token = authorization.substring(7);
		}
		boolean internalPath = request.getRequestURI().startsWith("/internal/");
		byte[] want = internalPath ? internal : expected;
		if (token != null && want.length > 0 && MessageDigest.isEqual(want, token.getBytes(StandardCharsets.UTF_8))) {
			if (internalPath) {
				SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken("svc_agent",
					null, List.of(new SimpleGrantedAuthority("ROLE_AGENT"))));
				chain.doFilter(request, response);
				return;
			}
			var auth = new UsernamePasswordAuthenticationToken("svc_simulator", null,
				List.of(new SimpleGrantedAuthority("ROLE_SERVICE")));
			SecurityContextHolder.getContext().setAuthentication(auth);
		}
		chain.doFilter(request, response);
	}
}
