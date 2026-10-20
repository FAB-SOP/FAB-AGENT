package com.fabcontext.api.auth;

import java.time.Instant;

import com.fabcontext.api.common.ApiException;
import com.fabcontext.api.common.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "auth")
@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
public class AuthController {

	private final AuthenticationManager authenticationManager;
	private final SecurityContextRepository securityContextRepository;

	@Operation(operationId = "getCsrf", summary = "CSRF 토큰 조회")
	@GetMapping("/csrf")
	public ApiResponse<AuthDtos.Csrf> getCsrf(CsrfToken csrfToken) {
		return ApiResponse.of(new AuthDtos.Csrf(csrfToken.getToken(), csrfToken.getHeaderName(),
			csrfToken.getParameterName()));
	}

	@Operation(operationId = "login", summary = "로그인")
	@PostMapping("/login")
	public ApiResponse<AuthDtos.Session> login(@RequestHeader(SecurityConfig.CSRF_HEADER) String csrfHeader,
			@Valid @RequestBody AuthDtos.Login body, HttpServletRequest request, HttpServletResponse response) {
		Authentication authentication;
		try {
			authentication = authenticationManager.authenticate(
				UsernamePasswordAuthenticationToken.unauthenticated(body.username(), body.password()));
		}
		catch (AuthenticationException e) {
			throw ApiException.unauthorized("아이디 또는 비밀번호가 올바르지 않습니다.");
		}
		AuthUser principal = ((AuthUser) authentication.getPrincipal()).withoutPassword();
		// 세션 고정 공격 방지: 로그인 시 세션 ID 교체 (CSRF 토큰 속성은 유지된다)
		request.getSession(true);
		request.changeSessionId();

		SecurityContext context = SecurityContextHolder.createEmptyContext();
		context.setAuthentication(UsernamePasswordAuthenticationToken.authenticated(principal, null,
			principal.getAuthorities()));
		SecurityContextHolder.setContext(context);
		securityContextRepository.saveContext(context, request, response);
		return ApiResponse.of(AuthDtos.Session.of(principal, expiresAt(request.getSession(false))));
	}

	@Operation(operationId = "session", summary = "현재 사용자 조회")
	@GetMapping("/session")
	public ApiResponse<AuthDtos.Session> session(@AuthenticationPrincipal AuthUser user, HttpServletRequest request) {
		return ApiResponse.of(AuthDtos.Session.of(user, expiresAt(request.getSession(false))));
	}

	@Operation(operationId = "logout", summary = "로그아웃")
	@PostMapping("/logout")
	public ResponseEntity<Void> logout(@RequestHeader(SecurityConfig.CSRF_HEADER) String csrfHeader,
			HttpServletRequest request) {
		HttpSession session = request.getSession(false);
		if (session != null) {
			session.invalidate();
		}
		SecurityContextHolder.clearContext();
		return ResponseEntity.noContent().build();
	}

	private static Instant expiresAt(HttpSession session) {
		if (session == null || session.getMaxInactiveInterval() <= 0) {
			return null;
		}
		return Instant.ofEpochMilli(session.getLastAccessedTime()).plusSeconds(session.getMaxInactiveInterval());
	}
}
