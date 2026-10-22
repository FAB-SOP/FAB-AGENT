package com.fabcontext.api.domain.investigation;

import java.net.URI;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import com.fabcontext.api.auth.AuthUser;
import com.fabcontext.api.auth.SecurityConfig;
import com.fabcontext.api.common.*;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@Tag(name="investigation-followup")
@RestController
@RequiredArgsConstructor
public class FollowupAnalysisController {
	private final FollowupAnalysisService service;
	private final IdempotencyService idempotency;
	@Operation(summary="추가 분석에 선택할 작업 결과·회신·거절 사유", description="사건 조회 권한 필요. 조회만으로 검토 완료나 분석을 실행하지 않습니다.")
	@GetMapping("/api/incidents/{id}/investigation-results")
	public ApiResponse<PageResponse<FollowupDtos.Result>> results(@PathVariable String id,@AuthenticationPrincipal AuthUser user,
		@RequestParam(required=false) String cursor,@RequestParam(required=false) Integer size) { return ApiResponse.of(service.list(id,user,cursor,size)); }
	@Operation(summary="선택 결과로 수동 추가 분석", description="사건 조사 권한 및 최신 expectedIncidentVersion 필요. 같은 사건 결과 1~20개, 원문 합계 24,000자 이하. 기존 회신 대기는 동일 thread로 재개합니다. 같은 Idempotency-Key/본문은 최초 응답을 재생합니다.")
	@ApiResponses({
		@io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode="202", description="분석 접수", content=@Content(mediaType="application/json", schema=@Schema(implementation=FollowupDtos.AcceptedEnvelope.class))),
		@io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode="403", description="사건 조사 권한 없음", content=@Content(mediaType="application/json", schema=@Schema(implementation=ErrorEnvelope.class))),
		@io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode="409", description="VERSION_CONFLICT / ANALYSIS_IN_PROGRESS / PENDING_APPROVAL_REQUIRED / WAITING_INPUT_MISMATCH / ANALYSIS_RESUME_UNAVAILABLE / INCIDENT_CLOSED", content=@Content(mediaType="application/json", schema=@Schema(implementation=ErrorEnvelope.class))),
		@io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode="422", description="INVALID_SOURCE_REF / DUPLICATE_SOURCE_REF / FOLLOWUP_INPUT_TOO_LARGE / IDEMPOTENCY_KEY_REUSED", content=@Content(mediaType="application/json", schema=@Schema(implementation=ErrorEnvelope.class)))
	})
	@PostMapping("/api/incidents/{id}/followup-analysis-jobs")
	public ResponseEntity<?> start(@PathVariable String id,@AuthenticationPrincipal AuthUser user,
		@RequestHeader(SecurityConfig.CSRF_HEADER) String csrf,@RequestHeader(IdempotencyService.HEADER) String key,@Valid @RequestBody FollowupDtos.Request body) {
		return idempotency.execute(user.userId(),"followupAnalysis:"+id,key,body,()->{
			var accepted=service.start(id,user,body);
			return ResponseEntity.accepted().location(URI.create(accepted.statusUrl())).body(ApiResponse.of(accepted));
		});
	}
	@Operation(summary="추가 분석 상태와 선택 결과 반영 추적", description="includedInInput은 입력 전달, citedInResult는 실제 분석 문장 인용 여부입니다. 결과 전에는 null입니다. 사건 조회 권한 필요.")
	@GetMapping("/api/analysis-requests/{id}")
	public ApiResponse<FollowupDtos.View> get(@PathVariable String id,@AuthenticationPrincipal AuthUser user) { return ApiResponse.of(service.get(id,user)); }
}
