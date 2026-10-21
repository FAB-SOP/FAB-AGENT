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
	public ApiResponse<?> list(@PathVariable String id,@AuthenticationPrincipal AuthUser user) {return ApiResponse.of(Map.of("items",service.list(id,user)));}
	@GetMapping("/api/work-items")
	public ApiResponse<?> inbox(@AuthenticationPrincipal AuthUser user,@RequestParam(defaultValue="mine") String box) {return ApiResponse.of(Map.of("items",service.inbox(user,box)));}
	@PostMapping("/api/incidents/{id}/work-items")
	public ResponseEntity<?> propose(@PathVariable String id,@AuthenticationPrincipal AuthUser user,@RequestHeader(SecurityConfig.CSRF_HEADER) String csrf,
		@RequestHeader(IdempotencyService.HEADER) String key,@Valid @RequestBody WorkDtos.Proposal body) {
		return idempotency.execute(user.userId(),"proposeWork:"+id,key,body,()->ResponseEntity.status(201).body(ApiResponse.of(service.propose(id,user,body))));
	}
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
	@PostMapping("/api/work-items/{id}/completions")
	public ResponseEntity<?> complete(@PathVariable String id,@AuthenticationPrincipal AuthUser user,@RequestHeader(SecurityConfig.CSRF_HEADER) String csrf,
		@RequestHeader(IdempotencyService.HEADER) String key,@Valid @RequestBody WorkDtos.Completion body) {
		return idempotency.execute(user.userId(),"completeWork:"+id,key,body,()->ResponseEntity.ok(ApiResponse.of(service.complete(id,user,body))));
	}
}
