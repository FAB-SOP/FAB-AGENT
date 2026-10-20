package com.fabcontext.api.domain.action;

import java.net.URI;

import com.fabcontext.api.auth.AuthUser;
import com.fabcontext.api.auth.SecurityConfig;
import com.fabcontext.api.common.ApiResponse;
import com.fabcontext.api.common.IdempotencyService;
import com.fabcontext.api.domain.action.ActionDtos.ActionInput;
import com.fabcontext.api.domain.action.ActionDtos.ActionPage;
import com.fabcontext.api.domain.action.ActionDtos.VerificationInput;
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
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
public class ActionController {

	private final ActionService service;
	private final IdempotencyService idempotency;

	@Tag(name = "incidents")
	@Operation(operationId = "listActions", summary = "사건 실제 조치 조회")
	@GetMapping("/api/incidents/{incidentId}/actions")
	public ApiResponse<ActionPage> listActions(@AuthenticationPrincipal AuthUser user, @PathVariable String incidentId) {
		return ApiResponse.of(service.list(incidentId, user));
	}

	@Tag(name = "incidents")
	@Operation(operationId = "recordAction", summary = "사람의 실제 조치 등록")
	@PostMapping("/api/incidents/{incidentId}/actions")
	public ResponseEntity<?> recordAction(@AuthenticationPrincipal AuthUser user, @PathVariable String incidentId,
			@RequestHeader(SecurityConfig.CSRF_HEADER) String csrf,
			@RequestHeader(IdempotencyService.HEADER) String idempotencyKey, @Valid @RequestBody ActionInput body) {
		return idempotency.execute(user.userId(), "recordAction:" + incidentId, idempotencyKey, body, () -> {
			var action = service.record(incidentId, user, body);
			return ResponseEntity.created(URI.create("/api/incidents/" + incidentId + "/actions"))
				.body(ApiResponse.of(action));
		});
	}

	@Tag(name = "actions")
	@Operation(operationId = "verifyAction", summary = "조치 효과 확인 등록")
	@PostMapping("/api/actions/{actionId}/verifications")
	public ResponseEntity<?> verifyAction(@AuthenticationPrincipal AuthUser user, @PathVariable String actionId,
			@RequestHeader(SecurityConfig.CSRF_HEADER) String csrf,
			@RequestHeader(IdempotencyService.HEADER) String idempotencyKey,
			@Valid @RequestBody VerificationInput body) {
		return idempotency.execute(user.userId(), "verifyAction:" + actionId, idempotencyKey, body,
			() -> ResponseEntity.status(201).body(ApiResponse.of(service.verify(actionId, user, body))));
	}
}
