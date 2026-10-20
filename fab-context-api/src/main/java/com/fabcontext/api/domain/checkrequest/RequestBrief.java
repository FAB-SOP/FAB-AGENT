package com.fabcontext.api.domain.checkrequest;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.fabcontext.api.common.ApiException;
import com.fabcontext.api.common.Json;

/**
 * 요청 브리프 — 확인 요청을 받는 사람이 「왜 내 장비가 의심받는지 · 얼마나 벗어났는지 · 어떤 원인을 생각하는지」를 아는 데 필요한 것.
 * Agent(또는 시뮬레이터)가 제안 payload.brief에 넣고, 승인 해시(actionHash)에 포함되며, 승인 뒤 확인 요청에 그대로 복사된다.
 * 받는 사람은 이 브리프만 보고 사건 분석 전체(P07) 권한은 여전히 없다.
 * <pre>
 * {reason, deviations:[{runId, equipmentId, processCode, metric, value, unit, baselineMean, z, threshold, thresholdKind,
 *   withinNormal, setpoint?, deviationFromSetpointPct?}], suspectedCauses:[{causeId, candidate, confirmed:false}],
 *   relatedRecords:[{evidenceId, summary, rootCauseConfirmed?}], askFor:[...]}
 * </pre>
 */
public final class RequestBrief {

	static final Set<String> KEYS = Set.of("reason", "deviations", "suspectedCauses", "relatedRecords", "askFor");

	static final int MAX_CHARS = 20000;

	private RequestBrief() {
	}

	/** 모양 검증 · 정규화. 원인 확정(confirmed=true)은 받지 않는다 — 확인 요청은 확인을 부탁하는 것이지 원인을 알리는 것이 아니다 */
	@SuppressWarnings("unchecked")
	public static Map<String, Object> validate(Object raw) {
		if (!(raw instanceof Map<?, ?> m)) {
			throw ApiException.unprocessable("INVALID_BRIEF", "payload.brief는 객체여야 합니다.");
		}
		Map<String, Object> brief = new LinkedHashMap<>();
		for (Map.Entry<?, ?> e : m.entrySet()) {
			String key = String.valueOf(e.getKey());
			if (!KEYS.contains(key)) {
				throw ApiException.unprocessable("INVALID_BRIEF", "payload.brief에 허용되지 않은 키: " + key + " (허용: " + KEYS + ")");
			}
			brief.put(key, e.getValue());
		}
		if (!(brief.get("reason") instanceof String reason) || reason.isBlank()) {
			throw ApiException.unprocessable("INVALID_BRIEF", "payload.brief.reason(왜 요청하는지 1~2문장)이 필요합니다.");
		}
		for (String list : List.of("deviations", "suspectedCauses", "relatedRecords", "askFor")) {
			Object v = brief.getOrDefault(list, List.of());
			if (!(v instanceof List<?>)) {
				throw ApiException.unprocessable("INVALID_BRIEF", "payload.brief." + list + "는 배열이어야 합니다.");
			}
			brief.put(list, v);
		}
		List<Map<String, Object>> causes = new ArrayList<>();
		for (Object c : (List<?>) brief.get("suspectedCauses")) {
			if (!(c instanceof Map<?, ?> cm)) {
				throw ApiException.unprocessable("INVALID_BRIEF", "suspectedCauses 항목은 객체여야 합니다.");
			}
			if (Boolean.TRUE.equals(cm.get("confirmed"))) {
				throw ApiException.unprocessable("INVALID_BRIEF", "확인 요청 브리프의 원인 후보는 확정(confirmed=true)일 수 없습니다.");
			}
			Map<String, Object> copy = new LinkedHashMap<>((Map<String, Object>) cm);
			copy.put("confirmed", false);
			causes.add(copy);
		}
		brief.put("suspectedCauses", causes);
		if (Json.write(brief).length() > MAX_CHARS) {
			throw ApiException.unprocessable("INVALID_BRIEF", "payload.brief가 너무 깁니다(" + MAX_CHARS + "자 이하).");
		}
		return brief;
	}
}
