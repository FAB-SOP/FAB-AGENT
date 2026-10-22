package com.fabcontext.api.auth;

import java.io.IOException;

import com.fabcontext.api.common.ErrorEnvelope;
import com.fabcontext.api.common.Json;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.MediaType;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.security.web.csrf.CsrfException;

/** 401/403을 공통 ErrorEnvelope JSON으로 응답 */
final class JsonSecurityHandlers {

	private JsonSecurityHandlers() {
	}

	static AuthenticationEntryPoint entryPoint() {
		return (request, response, ex) -> write(response, HttpServletResponse.SC_UNAUTHORIZED, "UNAUTHENTICATED",
			"로그인이 필요합니다.");
	}

	static AccessDeniedHandler accessDenied() {
		return (request, response, ex) -> {
			if (ex instanceof CsrfException) {
				write(response, HttpServletResponse.SC_FORBIDDEN, "CSRF_INVALID",
					"CSRF 토큰이 없거나 올바르지 않습니다. GET /api/auth/csrf로 다시 받으세요.");
			}
			else {
				write(response, HttpServletResponse.SC_FORBIDDEN, "FORBIDDEN", "접근 권한이 없습니다.");
			}
		};
	}

	private static void write(HttpServletResponse response, int status, String code, String message)
			throws IOException {
		response.setStatus(status);
		response.setContentType(MediaType.APPLICATION_JSON_VALUE);
		response.setCharacterEncoding("UTF-8");
		response.getWriter().write(Json.write(ErrorEnvelope.of(code, message, null)));
	}
}
