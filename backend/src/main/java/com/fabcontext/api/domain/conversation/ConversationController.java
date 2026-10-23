package com.fabcontext.api.domain.conversation;

import com.fabcontext.api.auth.AuthUser;
import com.fabcontext.api.common.ApiResponse;
import com.fabcontext.api.common.ErrorEnvelope;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@Tag(name = "incident-conversation")
@RestController
@RequiredArgsConstructor
public class ConversationController {
    private final ConversationService service;
    @Operation(operationId = "getIncidentConversation", summary = "사건 대화·협업 기록 통합 조회",
            description = "사건 전체 조회 권한 필요. 최근 size개를 오래된 순으로 반환하고 nextCursor로 이전 기록을 조회합니다. 질문·답·승인·회신·작업 결과와 작업 연결을 묶어 제공합니다. 조회는 읽음·승인·분석·상태 변경을 수행하지 않습니다. 기존 사건 SSE 수신 시 재조회하며 별도 폴링·Kafka는 추가하지 않습니다.")
    @ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ConversationDtos.Envelope.class))),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "INVALID_CURSOR / INVALID_PAGE_SIZE", content = @Content(schema = @Schema(implementation = ErrorEnvelope.class))),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "UNAUTHENTICATED", content = @Content(schema = @Schema(implementation = ErrorEnvelope.class))),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "기존 사건 조회 권한 없음. 요청 수신 관계만으로 전체 대화를 볼 수 없음", content = @Content(schema = @Schema(implementation = ErrorEnvelope.class))),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "INCIDENT_NOT_FOUND", content = @Content(schema = @Schema(implementation = ErrorEnvelope.class)))
    })
    @GetMapping("/api/incidents/{incidentId}/conversation")
    public ApiResponse<ConversationDtos.View> get(@PathVariable String incidentId, @AuthenticationPrincipal AuthUser user,
            @Parameter(description = "이전 기록 더보기 커서. 최초 조회는 생략") @RequestParam(required = false) String cursor,
            @Parameter(description = "최근 기록 개수, 기본 50, 1~100") @RequestParam(required = false) Integer size) {
        return ApiResponse.of(service.get(incidentId, user, cursor, size));
    }
}
