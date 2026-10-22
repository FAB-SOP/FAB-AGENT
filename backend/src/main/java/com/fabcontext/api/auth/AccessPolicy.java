package com.fabcontext.api.auth;

import com.fabcontext.api.common.ApiException;
import org.springframework.stereotype.Component;

/** 역할·공정 범위 권한 경계. 담당 관계(요청 owner 등)는 각 서비스에서 확인한다. */
@Component
public class AccessPolicy {

	public void requireScope(AuthUser user, String processCode) {
		if (!user.hasScope(processCode)) {
			throw ApiException.forbidden("PROCESS_SCOPE_DENIED", "이 공정(" + processCode + ")에 대한 권한이 없습니다.");
		}
	}

	public void requireInvestigationScope(AuthUser user, String processCode) {
		requireScope(user, processCode);
		if (!user.investigationProcessScopes().contains(processCode)) {
			throw ApiException.forbidden("INVESTIGATION_WRITE_DENIED", "이 사건의 분석·요청·기록은 담당 공정 운영자만 변경할 수 있습니다.");
		}
	}

	public void requireManager(AuthUser user) {
		if (!user.isManager()) {
			throw ApiException.forbidden("MANAGER_ROLE_REQUIRED", "관리자(MANAGER) 권한이 필요합니다.");
		}
	}
}
