package com.fabcontext.api.domain.job;

import com.fabcontext.api.auth.AuthUser;
import com.fabcontext.api.common.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "jobs")
@RestController
@RequiredArgsConstructor
public class JobController {

	private final JobService jobService;

	@Operation(operationId = "getJob", summary = "분석/재개 작업 상태")
	@GetMapping("/api/jobs/{jobId}")
	public ApiResponse<JobDtos.JobView> getJob(@AuthenticationPrincipal AuthUser user, @PathVariable String jobId) {
		return ApiResponse.of(jobService.get(jobId, user));
	}
}
