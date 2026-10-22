package com.fabcontext.api.domain.approval;

import java.net.URI;

import com.fabcontext.api.auth.AuthUser;
import com.fabcontext.api.auth.SecurityConfig;
import com.fabcontext.api.common.ApiResponse;
import com.fabcontext.api.common.IdempotencyService;
import com.fabcontext.api.common.PageResponse;
import com.fabcontext.api.domain.approval.ApprovalDtos.ApprovalDecision;
import com.fabcontext.api.domain.approval.ApprovalDtos.ApprovalSummary;
import com.fabcontext.api.domain.approval.ApprovalDtos.ApprovalView;
import com.fabcontext.api.domain.approval.ApprovalDtos.Proposal;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
public class ApprovalController {

	private final ApprovalService service;
	private final IdempotencyService idempotency;

	@Tag(name = "approvals")
	@Operation(operationId = "listApprovals", summary = "내 승인 목록")
	@GetMapping("/api/approvals")
	public ApiResponse<PageResponse<ApprovalSummary>> listApprovals(@AuthenticationPrincipal AuthUser user,
			@RequestParam(required = false) String status, @RequestParam(required = false) String incidentId,
			@RequestParam(required = false) String cursor, @RequestParam(required = false) Integer size) {
		return ApiResponse.of(service.list(user, status, incidentId, cursor, size));
	}

	@Tag(name = "approvals")
	@Operation(operationId = "getApproval", summary = "변경 제안 상세")
	@GetMapping("/api/approvals/{approvalId}")
	public ApiResponse<ApprovalView> getApproval(@AuthenticationPrincipal AuthUser user,
			@PathVariable String approvalId) {
		return ApiResponse.of(service.get(approvalId, user));
	}

	@Tag(name = "incidents")
	@Operation(operationId = "createProposal", summary = "수동 확인 요청 등의 승인 초안 생성")
	@PostMapping("/api/incidents/{incidentId}/proposals")
	public ResponseEntity<ApiResponse<ApprovalView>> createProposal(@AuthenticationPrincipal AuthUser user,
			@PathVariable String incidentId, @RequestHeader(SecurityConfig.CSRF_HEADER) String csrf,
			@Valid @RequestBody Proposal body) {
		ApprovalView view = service.createProposal(incidentId, user, body);
		return ResponseEntity.created(URI.create("/api/approvals/" + view.approvalId())).body(ApiResponse.of(view));
	}

	@Tag(name = "approvals")
	@Operation(operationId = "decideApproval", summary = "정확한 변경 제안 승인/거절")
	@PostMapping("/api/approvals/{approvalId}/decisions")
	public ResponseEntity<?> decideApproval(@AuthenticationPrincipal AuthUser user, @PathVariable String approvalId,
			@RequestHeader(SecurityConfig.CSRF_HEADER) String csrf,
			@RequestHeader(IdempotencyService.HEADER) String idempotencyKey,
			@Valid @RequestBody ApprovalDecision body) {
		return idempotency.execute(user.userId(), "decideApproval:" + approvalId, idempotencyKey, body,
			() -> ResponseEntity.ok(ApiResponse.of(service.decide(approvalId, user, body))));
	}
}
