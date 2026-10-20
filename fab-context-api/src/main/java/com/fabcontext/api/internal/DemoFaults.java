package com.fabcontext.api.internal;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.stereotype.Component;

/**
 * 시연 · 평가용 장애 주입 — 도구 이름별로 남은 실패 횟수만큼 UNAVAILABLE(재시도 대상)을 돌려준다.
 * /internal/demo/faults(fab.demo-routes=true일 때만)로만 설정할 수 있고, 비어 있으면 아무 일도 하지 않는다.
 */
@Component
public class DemoFaults {

	private final Map<String, Integer> remaining = new ConcurrentHashMap<>();

	public void set(String tool, int count) {
		if (count <= 0) {
			remaining.remove(tool);
		}
		else {
			remaining.put(tool, count);
		}
	}

	public Map<String, Integer> snapshot() {
		return Map.copyOf(remaining);
	}

	public void clear() {
		remaining.clear();
	}

	void maybeFail(String tool) {
		Integer left = remaining.computeIfPresent(tool, (k, v) -> v - 1);
		if (left != null) {
			if (left <= 0) {
				remaining.remove(tool);
			}
			throw new InternalError("UNAVAILABLE", tool + " 내부 API가 일시적으로 응답하지 않습니다.", "잠시 후 다시 시도하세요.", true);
		}
	}
}
