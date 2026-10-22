package com.fabcontext.api.domain.dashboard;

import com.fabcontext.api.auth.AuthUser;
import com.fabcontext.api.common.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "dashboard")
@RestController
@RequiredArgsConstructor
public class DashboardController {

	private final DashboardService service;

	@Operation(operationId = "dashboard", summary = "권한 범위 대시보드")
	@GetMapping("/api/dashboard")
	public ApiResponse<DashboardService.Dashboard> dashboard(@AuthenticationPrincipal AuthUser user,
			@RequestParam(required = false) String period, @RequestParam(required = false) String processCode) {
		return ApiResponse.of(service.get(user, period, processCode));
	}
}
