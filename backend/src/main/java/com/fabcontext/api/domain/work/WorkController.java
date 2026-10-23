package com.fabcontext.api.domain.work;
import java.util.Map;
import com.fabcontext.api.auth.AuthUser;
import com.fabcontext.api.common.ApiResponse;
import com.fabcontext.api.common.IdempotencyService;
import com.fabcontext.api.auth.SecurityConfig;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequiredArgsConstructor
public class WorkController {
	private final WorkService service;
	private final com.fabcontext.api.domain.checkrequest.CheckRequestService requests;
	private final IdempotencyService idempotency;
	@GetMapping("/api/incidents/{id}/check-requests")
	public ApiResponse<Map<String,java.util.List<com.fabcontext.api.domain.checkrequest.CheckRequestDtos.CheckRequestView>>> requests(@PathVariable String id,@AuthenticationPrincipal AuthUser user) {return ApiResponse.of(Map.of("items",requests.forIncident(id,user)));}
	@GetMapping("/api/work-policy")
	public ApiResponse<?> policy() {return ApiResponse.of(service.policy());}
	@GetMapping("/api/incidents/{id}/work-items")
	public ApiResponse<Map<String,java.util.List<WorkDtos.View>>> list(@PathVariable String id,@AuthenticationPrincipal AuthUser user) {return ApiResponse.of(Map.of("items",service.list(id,user)));}
	public record WorkInbox(java.util.List<WorkDtos.View> items,
		java.util.List<com.fabcontext.api.domain.checkrequest.CheckRequestDtos.CheckRequestSummary> incomingRequests) {}
	@io.swagger.v3.oas.annotations.Operation(summary="내 작업·승인·보낸 업무함", description="box=mine일 때 incomingRequests에 본인이 수락한 확인 요청을 별도 제공합니다. 기존 items와 requestId로 연결할 수 있습니다. 다른 box는 incomingRequests가 빈 배열입니다.")
	@GetMapping("/api/work-items")
	public ApiResponse<WorkInbox> inbox(@AuthenticationPrincipal AuthUser user,@RequestParam(defaultValue="mine") String box) {return ApiResponse.of(new WorkInbox(service.inbox(user,box),"mine".equals(box)?requests.acceptedInbox(user):java.util.List.of()));}
	@PostMapping("/api/incidents/{id}/work-items")
	public ResponseEntity<?> propose(@PathVariable String id,@AuthenticationPrincipal AuthUser user,@RequestHeader(SecurityConfig.CSRF_HEADER) String csrf,
		@RequestHeader(IdempotencyService.HEADER) String key,@Valid @RequestBody WorkDtos.Proposal body) {
		return idempotency.execute(user.userId(),"proposeWork:"+id,key,body,()->ResponseEntity.status(201).body(ApiResponse.of(service.propose(id,user,body))));
	}
	@io.swagger.v3.oas.annotations.Operation(summary="업무 제안 채택", description="상급자 승인 요청과 다른 팀 확인 요청은 원본 분석의 실제 계측·정상 기준·관측 요약·요청 사유·확인할 항목을 brief로 보존합니다. 전체 분석 요약은 복사하지 않으며 대상 공정의 관련 후보만 미확정으로 표시합니다. 기존 업무/직접 확인 업무는 brief가 null일 수 있습니다.")
	@io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode="200", content=@io.swagger.v3.oas.annotations.media.Content(mediaType="application/json",schema=@io.swagger.v3.oas.annotations.media.Schema(implementation=WorkDtos.Envelope.class)))
	@PostMapping("/api/work-items/{id}/adoptions")
	public ResponseEntity<?> adopt(@PathVariable String id,@AuthenticationPrincipal AuthUser user,@RequestHeader(SecurityConfig.CSRF_HEADER) String csrf,
		@RequestHeader(IdempotencyService.HEADER) String key,@Valid @RequestBody WorkDtos.Adoption body) {
		return idempotency.execute(user.userId(),"adoptWork:"+id,key,body,()->ResponseEntity.ok(ApiResponse.of(service.adopt(id,user,body))));
	}
	@PostMapping("/api/work-items/{id}/decisions")
	public ResponseEntity<?> decide(@PathVariable String id,@AuthenticationPrincipal AuthUser user,@RequestHeader(SecurityConfig.CSRF_HEADER) String csrf,
		@RequestHeader(IdempotencyService.HEADER) String key,@Valid @RequestBody WorkDtos.Decision body) {
		return idempotency.execute(user.userId(),"decideWork:"+id,key,body,()->ResponseEntity.ok(ApiResponse.of(service.decide(id,user,body))));
	}
	@io.swagger.v3.oas.annotations.Operation(operationId="closeWorkItemUnperformed",summary="채택한 업무를 미수행으로 닫기",
		description="READY · APPROVED · REQUESTED에서만. 담당자 또는 사건 운영 권한자가 사유와 함께 닫으면 CLOSED_UNPERFORMED. 연결된 확인 요청은 바꾸지 않는다.")
	@PostMapping("/api/work-items/{id}/unperformed-closures")
	public ResponseEntity<?> closeUnperformed(@PathVariable String id,@AuthenticationPrincipal AuthUser user,@RequestHeader(SecurityConfig.CSRF_HEADER) String csrf,
		@RequestHeader(IdempotencyService.HEADER) String key,@Valid @RequestBody WorkDtos.UnperformedClosure body) {
		return idempotency.execute(user.userId(),"closeWorkUnperformed:"+id,key,body,()->ResponseEntity.ok(ApiResponse.of(service.closeUnperformed(id,user,body))));
	}
	@PostMapping("/api/work-items/{id}/completions")
	public ResponseEntity<?> complete(@PathVariable String id,@AuthenticationPrincipal AuthUser user,@RequestHeader(SecurityConfig.CSRF_HEADER) String csrf,
		@RequestHeader(IdempotencyService.HEADER) String key,@Valid @RequestBody WorkDtos.Completion body) {
		return idempotency.execute(user.userId(),"completeWork:"+id,key,body,()->ResponseEntity.ok(ApiResponse.of(service.complete(id,user,body))));
	}
}
