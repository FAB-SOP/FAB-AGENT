package com.fabcontext.api.internal;

import java.util.Map;

import com.fabcontext.api.common.ApiResponse;
import io.swagger.v3.oas.annotations.Hidden;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 시연 · 평가 전용 — 장애 주입. fab.demo-routes=true(DEMO_ROUTES)일 때만 열린다. 인증은 내부 토큰(ROLE_AGENT) */
@Hidden
@RestController
@RequestMapping("/internal/demo")
@RequiredArgsConstructor
@ConditionalOnProperty(name = "fab.demo-routes", havingValue = "true")
class DemoController {

	private final DemoFaults faults;

	/** {tool, count} — count번 UNAVAILABLE. count=0이면 해제, tool="*"이면 전부 해제 */
	@PostMapping("/faults")
	ApiResponse<Map<String, Integer>> fault(@RequestBody Map<String, Object> body) {
		String tool = String.valueOf(body.get("tool"));
		if ("*".equals(tool)) {
			faults.clear();
		}
		else {
			faults.set(tool, ((Number) body.getOrDefault("count", 1)).intValue());
		}
		return ApiResponse.of(faults.snapshot());
	}

	@GetMapping("/faults")
	ApiResponse<Map<String, Integer>> faults() {
		return ApiResponse.of(faults.snapshot());
	}
}
