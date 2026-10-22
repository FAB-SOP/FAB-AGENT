package com.fabcontext.api.auth;

import java.io.Serial;
import java.io.Serializable;
import java.util.Collection;
import java.util.List;

import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;

/** 세션에 저장되는 인증 주체 */
public record AuthUser(String userId, String username, String displayName, Role role, List<String> processScopes,
		String team, String passwordHash) implements UserDetails, Serializable {

	@Serial
	private static final long serialVersionUID = 1L;

	public static AuthUser from(UserAccount account) {
		return new AuthUser(account.getUserId(), account.getUsername(), account.getDisplayName(), account.getRole(),
			List.copyOf(account.getProcessScopes()), account.getTeam(), account.getPasswordHash());
	}

	public boolean isManager() {
		return role == Role.MANAGER;
	}

	/** 정비팀은 조회·배정 요청 회신을 담당하고 사건 운영은 공정 담당자가 맡는다. */
	public List<String> investigationProcessScopes() {
		return !isManager() && "설비정비팀".equals(team) ? List.of() : processScopes;
	}

	public boolean hasScope(String processCode) {
		return processCode != null && processScopes.contains(processCode);
	}

	@Override
	public Collection<? extends GrantedAuthority> getAuthorities() {
		return List.of(new SimpleGrantedAuthority("ROLE_" + role.name()));
	}

	@Override
	public String getPassword() {
		return passwordHash;
	}

	@Override
	public String getUsername() {
		return username;
	}

	/** 비밀번호 해시를 제외한 사본 (세션 저장용) */
	public AuthUser withoutPassword() {
		return new AuthUser(userId, username, displayName, role, processScopes, team, null);
	}
}
