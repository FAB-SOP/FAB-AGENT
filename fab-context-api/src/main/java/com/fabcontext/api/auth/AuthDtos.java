package com.fabcontext.api.auth;

import java.time.Instant;
import java.util.List;

import jakarta.validation.constraints.NotBlank;

public final class AuthDtos {

	private AuthDtos() {
	}

	public record Csrf(String token, String headerName, String parameterName) {
	}

	public record Login(@NotBlank String username, @NotBlank String password) {
	}

	public record SessionUser(String userId, String username, String displayName, Role role,
			List<String> processScopes, String team) {

		static SessionUser from(AuthUser user) {
			return new SessionUser(user.userId(), user.username(), user.displayName(), user.role(),
				user.processScopes(), user.team());
		}
	}

	/** 세션 응답. role · processScopes는 user 안과 최상위 양쪽에 둔다(화면 매핑 호환). */
	public record Session(SessionUser user, Role role, List<String> processScopes, boolean authenticated,
			Instant expiresAt, List<String> investigationProcessScopes) {

		static Session of(AuthUser user, Instant expiresAt) {
			return new Session(SessionUser.from(user), user.role(), user.processScopes(), true, expiresAt, user.investigationProcessScopes());
		}
	}
}
