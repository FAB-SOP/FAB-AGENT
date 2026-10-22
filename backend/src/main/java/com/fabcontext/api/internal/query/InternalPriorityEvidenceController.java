package com.fabcontext.api.internal.query;

import java.time.Instant;
import java.util.Map;
import com.fabcontext.api.common.ApiResponse;
import com.fabcontext.api.internal.InternalApi;
import io.swagger.v3.oas.annotations.Hidden;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.*;

@Hidden
@RestController
@RequiredArgsConstructor
public class InternalPriorityEvidenceController implements InternalApi {
	private final InternalPriorityEvidenceQuery query;
	@GetMapping("/internal/runs/{runId}/priority-evidence")
	public ApiResponse<Map<String,Object>> evidence(@PathVariable String runId,
		@RequestParam String policyVersion,
		@RequestParam(required=false) @DateTimeFormat(iso=DateTimeFormat.ISO.DATE_TIME) Instant asOf,
		@RequestParam(defaultValue="false") boolean includeDetails) {
		return ApiResponse.of(query.evidence(runId,asOf,policyVersion,includeDetails));
	}
}
