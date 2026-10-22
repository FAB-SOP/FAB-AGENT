package com.fabcontext.api.domain.checkrequest;

import com.fabcontext.api.auth.UserAccountRepository;
import com.fabcontext.api.domain.approval.ApprovalActionType;
import org.springframework.stereotype.Component;

/** CHECK_REQUEST — 다른 공정 담당자 확인 요청 */
@Component
class CrossProcessCheckHandler extends CheckRequestActionHandler {

	CrossProcessCheckHandler(UserAccountRepository userRepository, CheckRequestService checkRequestService) {
		super(userRepository, checkRequestService, "CROSS_PROCESS");
	}

	@Override
	public ApprovalActionType type() {
		return ApprovalActionType.CHECK_REQUEST;
	}
}
