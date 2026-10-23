package com.fabcontext.api.domain.investigation;

import java.net.URI;

import com.fabcontext.api.auth.AuthUser;
import com.fabcontext.api.auth.SecurityConfig;
import com.fabcontext.api.common.ApiResponse;
import com.fabcontext.api.common.ErrorEnvelope;
import com.fabcontext.api.common.IdempotencyService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@Tag(name = "investigation-requests")
@RestController
@RequiredArgsConstructor
public class RequestDraftController {
    private final RequestDraftService service;
    private final IdempotencyService idempotency;

    @Operation(operationId = "createRequestDraftJob", summary = "확인 요청서 생성 작업 접수",
            description = "기준 분석과 대상 공정으로 DEEP/REQUEST 작업을 만듭니다. 요청서를 자동 발송하지 않습니다. 사람의 승인 후 기존 경로로 발송합니다. 사건 조사 권한·CSRF·Idempotency-Key 필요. 대상은 현재 공정 또는 실제 앞 공정이며 승인 대기 중인 Agent 요청서가 있으면 기존 카드를 먼저 처리해야 합니다.")
    @ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "202", description = "요청서 작성 작업 접수. Location은 기존 작업 조회 URL", content = @Content(mediaType = "application/json", schema = @Schema(implementation = RequestDraftDtos.AcceptedEnvelope.class))),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "UNAUTHENTICATED", content = @Content(schema = @Schema(implementation = ErrorEnvelope.class))),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "기존 사건 공정·조사 운영 권한 또는 CSRF 검사 실패", content = @Content(schema = @Schema(implementation = ErrorEnvelope.class))),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "INCIDENT_NOT_FOUND", content = @Content(schema = @Schema(implementation = ErrorEnvelope.class))),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "409", description = "VERSION_CONFLICT / JOB_IN_PROGRESS / AGENT_APPROVAL_PENDING / INCIDENT_CLOSED", content = @Content(schema = @Schema(implementation = ErrorEnvelope.class))),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "422", description = "VALIDATION_FAILED / REQUEST_SOURCE_INVALID / REQUEST_CAUSE_INVALID / DUPLICATE_CAUSE_ID / REQUEST_TARGET_INVALID / IDEMPOTENCY_KEY_REUSED", content = @Content(schema = @Schema(implementation = ErrorEnvelope.class)))
    })
    @PostMapping("/api/incidents/{incidentId}/request-draft-jobs")
    public ResponseEntity<?> start(@PathVariable String incidentId, @AuthenticationPrincipal AuthUser user,
            @RequestHeader(SecurityConfig.CSRF_HEADER) String csrf,
            @RequestHeader(IdempotencyService.HEADER) String key, @Valid @RequestBody RequestDraftDtos.Request body) {
        return idempotency.execute(user.userId(), "requestDraft:" + incidentId, key, body, () -> {
            var accepted = service.start(incidentId, user, body);
            return ResponseEntity.accepted().location(URI.create(accepted.statusUrl())).body(ApiResponse.of(accepted));
        });
    }
}
