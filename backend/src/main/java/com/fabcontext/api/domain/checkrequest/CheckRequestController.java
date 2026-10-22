package com.fabcontext.api.domain.checkrequest;

import java.net.URI;

import com.fabcontext.api.auth.AuthUser;
import com.fabcontext.api.auth.SecurityConfig;
import com.fabcontext.api.common.ApiResponse;
import com.fabcontext.api.common.IdempotencyService;
import com.fabcontext.api.common.PageResponse;
import com.fabcontext.api.domain.checkrequest.CheckRequestDtos.CheckRequestSummary;
import com.fabcontext.api.domain.checkrequest.CheckRequestDtos.CheckRequestView;
import com.fabcontext.api.domain.checkrequest.CheckRequestDtos.ReplyInput;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import com.fabcontext.api.common.ErrorEnvelope;
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
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "check-requests")
@RestController
@RequestMapping("/api/check-requests")
@RequiredArgsConstructor
public class CheckRequestController {

	private final CheckRequestService service;
	private final IdempotencyService idempotency;

	@Operation(operationId = "listRequests", summary = "받은/보낸 요청")
	@GetMapping
	public ApiResponse<PageResponse<CheckRequestSummary>> listRequests(@AuthenticationPrincipal AuthUser user,
			@RequestParam(required = false) String box, @RequestParam(required = false) String status,
			@RequestParam(required = false) String incidentId, @RequestParam(required = false) String cursor,
			@RequestParam(required = false) Integer size) {
		return ApiResponse.of(service.list(user, box, status, incidentId, cursor, size));
	}

	@Operation(operationId = "getRequest", summary = "확인 요청 상세")
	@GetMapping("/{requestId}")
	public ApiResponse<CheckRequestView> getRequest(@AuthenticationPrincipal AuthUser user,
			@PathVariable String requestId) {
		return ApiResponse.of(service.get(requestId, user));
	}

	@Operation(summary="지정 수신자의 요청 수락·거절", description="OPEN에서만 가능. ACCEPT는 comment 선택, REJECT는 공백이 아닌 comment 필수. 자동 분석하지 않습니다.")
	@ApiResponses({
		@io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode="200", content=@Content(mediaType="application/json", schema=@Schema(implementation=CheckRequestDtos.DecisionEnvelope.class))),
		@io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode="403", description="NOT_REQUEST_OWNER", content=@Content(mediaType="application/json", schema=@Schema(implementation=ErrorEnvelope.class))),
		@io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode="409", description="VERSION_CONFLICT / REQUEST_STATE_CONFLICT / INCIDENT_CLOSED", content=@Content(mediaType="application/json", schema=@Schema(implementation=ErrorEnvelope.class))),
		@io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode="422", description="REJECT_REASON_REQUIRED", content=@Content(mediaType="application/json", schema=@Schema(implementation=ErrorEnvelope.class)))
	})
	@PostMapping("/{requestId}/decisions")
	public ResponseEntity<?> decide(@AuthenticationPrincipal AuthUser user,@PathVariable String requestId,
		@RequestHeader(SecurityConfig.CSRF_HEADER) String csrf,@RequestHeader(IdempotencyService.HEADER) String key,
		@Valid @RequestBody CheckRequestDtos.DecisionInput body) {
		return idempotency.execute(user.userId(),"decideRequest:"+requestId,key,body,
			()->ResponseEntity.ok(ApiResponse.of(service.decide(requestId,user,body))));
	}

	@Operation(operationId = "submitReply", summary = "담당자가 확인한 회신 등록", description="ACCEPTED의 지정 수신자만 회신. confirmedByUser=true, 실제 결과 필수. 회신 저장만 하며 자동 재분석하지 않습니다. resumeJobId는 null입니다.")
	@io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode="201", content=@Content(mediaType="application/json", schema=@Schema(implementation=CheckRequestDtos.ReplyEnvelope.class)))
	@PostMapping("/{requestId}/replies")
	public ResponseEntity<?> submitReply(@AuthenticationPrincipal AuthUser user, @PathVariable String requestId,
			@RequestHeader(SecurityConfig.CSRF_HEADER) String csrf,
			@RequestHeader(IdempotencyService.HEADER) String idempotencyKey, @Valid @RequestBody ReplyInput body) {
		return idempotency.execute(user.userId(), "submitReply:" + requestId, idempotencyKey, body, () -> {
			var result = service.reply(requestId, user, body);
			return ResponseEntity.created(URI.create("/api/check-requests/" + requestId))
				.body(ApiResponse.of(result));
		});
	}
}
