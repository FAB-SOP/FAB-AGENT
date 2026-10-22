package com.fabcontext.api.domain.checkrequest;

import com.fabcontext.api.auth.UserAccountRepository;
import com.fabcontext.api.domain.approval.ApprovalActionType;
import org.springframework.stereotype.Component;

/** MAINTENANCE_REQUEST — 같은 공정 설비정비팀 점검 요청 */
@Component
class MaintenanceRequestHandler extends CheckRequestActionHandler {

	MaintenanceRequestHandler(UserAccountRepository userRepository, CheckRequestService checkRequestService) {
		super(userRepository, checkRequestService, "MAINTENANCE");
	}

	@Override
	public ApprovalActionType type() {
		return ApprovalActionType.MAINTENANCE_REQUEST;
	}
}
