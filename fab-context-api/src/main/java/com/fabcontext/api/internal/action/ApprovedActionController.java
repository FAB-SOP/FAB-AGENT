package com.fabcontext.api.internal.action;

import java.util.Map;

import com.fabcontext.api.common.ApiResponse;
import com.fabcontext.api.domain.approval.ApprovalActionType;
import com.fabcontext.api.internal.InternalApi;
import io.swagger.v3.oas.annotations.Hidden;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 승인된 변경 실행 — approvalId + idempotencyKey만 받는다. 재개 값 조회(승인 하나) */
@Hidden
@RestController
@RequestMapping("/internal")
@RequiredArgsConstructor
class ApprovedActionController implements InternalApi {

	private final ApprovedActionService service;

	@PostMapping("/approved-actions/check-request")
	ApiResponse<Map<String, Object>> executeCheckRequest(@RequestBody Map<String, String> body) {
		return ApiResponse.of(service.executeApproved(ApprovalActionType.CHECK_REQUEST, body.get("approvalId"),
			body.get("idempotencyKey")));
	}

	@PostMapping("/approved-actions/maintenance-request")
	ApiResponse<Map<String, Object>> executeMaintenanceRequest(@RequestBody Map<String, String> body) {
		return ApiResponse.of(service.executeApproved(ApprovalActionType.MAINTENANCE_REQUEST, body.get("approvalId"),
			body.get("idempotencyKey")));
	}

	@PostMapping("/approved-actions/action-record")
	ApiResponse<Map<String, Object>> executeActionRecord(@RequestBody Map<String, String> body) {
		return ApiResponse.of(service.executeApproved(ApprovalActionType.RECORD_ACTION, body.get("approvalId"),
			body.get("idempotencyKey")));
	}

	@PostMapping("/approved-actions/report-confirmation")
	ApiResponse<Map<String, Object>> executeReportConfirmation(@RequestBody Map<String, String> body) {
		return ApiResponse.of(service.executeApproved(ApprovalActionType.CONFIRM_REPORT, body.get("approvalId"),
			body.get("idempotencyKey")));
	}

	@GetMapping("/approvals/{approvalId}")
	ApiResponse<Map<String, Object>> approval(@PathVariable String approvalId) {
		return ApiResponse.of(service.approval(approvalId));
	}
}
