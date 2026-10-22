package com.fabcontext.api.internal.action;

import java.util.List;
import java.util.Map;

import com.fabcontext.api.common.Ref;
import com.fabcontext.api.domain.action.IncidentActionRepository;
import com.fabcontext.api.domain.approval.ApprovalActionType;
import com.fabcontext.api.domain.approval.ApprovalService;
import com.fabcontext.api.domain.checkrequest.CheckRequestRepositories.CheckRequestRepository;
import com.fabcontext.api.domain.report.ReportRepository;
import com.fabcontext.api.internal.DemoFaults;
import com.fabcontext.api.internal.InternalError;
import com.fabcontext.api.internal.view.InternalViews;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 승인된 변경 실행(approvalId + idempotencyKey만) · 승인 상태 조회 — 재개된 Agent가 업무 MCP 변경 도구로 부른다 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ApprovedActionService {

	private final ApprovalService approvalService;
	private final CheckRequestRepository checkRequestRepository;
	private final IncidentActionRepository actionRepository;
	private final ReportRepository reportRepository;
	private final DemoFaults faults;
	private final InternalViews views;

	@Transactional
	public Map<String, Object> executeApproved(ApprovalActionType type, String approvalId, String idempotencyKey) {
		if (approvalId == null || idempotencyKey == null || idempotencyKey.length() < 8) {
			throw InternalError.invalid("approvalId와 idempotencyKey(8자 이상)가 필요합니다.", "");
		}
		faults.maybeFail(switch (type) {
			case CHECK_REQUEST -> "create_cross_process_check_request";
			case MAINTENANCE_REQUEST -> "create_maintenance_request";
			case CONFIRM_REPORT -> "confirm_report";
			case RECORD_ACTION -> "record_action_result";
		});
		Ref ref = approvalService.executeApproved(approvalId, type, idempotencyKey);
		Object data = switch (type) {
			case CHECK_REQUEST, MAINTENANCE_REQUEST -> views.requestView(checkRequestRepository.findById(ref.id()).orElseThrow());
			case RECORD_ACTION -> views.actionView(actionRepository.findById(ref.id()).orElseThrow());
			case CONFIRM_REPORT -> views.reportView(reportRepository.findById(ref.id()).orElseThrow());
		};
		return views.env(data, List.of(), List.of());
	}

	public Map<String, Object> approval(String approvalId) {
		return views.approvalView(approvalService.load(approvalId));
	}
}
