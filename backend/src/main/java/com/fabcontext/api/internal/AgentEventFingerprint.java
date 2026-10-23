package com.fabcontext.api.internal;

import java.math.BigDecimal;
import java.util.*;

import com.fabcontext.api.common.Hashing;
import com.fabcontext.api.domain.job.Job;

/** 전송 정보만 제외한다. 근거·보고서 스냅샷 안의 시각, 배열 순서는 업무 내용이므로 보존한다. */
final class AgentEventFingerprint {
    private AgentEventFingerprint() {}
    static String of(Job job, Map<String, Object> event) {
        Map<String, Object> content = new TreeMap<>(event);
        for (String field : List.of("eventId", "at", "sequence", "metrics", "traceId")) content.remove(field);
        content.putIfAbsent("schemaVersion", "1.0");
        content.putIfAbsent("jobId", job.getJobId());
        content.putIfAbsent("incidentId", job.getIncidentId());
        content.putIfAbsent("threadId", job.getThreadId());
        Object raw = event.getOrDefault("payload", Map.of());
        String type = String.valueOf(event.get("type"));
        String ignored = "JOB_STARTED".equals(type) ? "startedAt" : "RETRY_SCHEDULED".equals(type) ? "nextRetryAt" : null;
        if (raw instanceof Map<?, ?> payload && ignored != null) {
            Map<String, Object> clean = new TreeMap<>();
            payload.forEach((k, v) -> { if (!ignored.equals(k)) clean.put(k.toString(), v); });
            content.put("payload", clean);
        } else content.put("payload", raw);
        String hash = Hashing.canonicalSha256(normalize(content));
        // v1 RETRY 행은 nextRetryAt을 포함했다. 다른 이벤트 해시는 그대로 두고 변경된 종류만 구분한다.
        return "RETRY_SCHEDULED".equals(type) ? "v2:" + hash : hash;
    }
    private static Object normalize(Object value) {
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> sorted = new TreeMap<>();
            map.forEach((k, v) -> sorted.put(k.toString(), normalize(v)));
            return sorted;
        }
        if (value instanceof List<?> list) return list.stream().map(AgentEventFingerprint::normalize).toList();
        if (value instanceof Number number) return new BigDecimal(number.toString()).stripTrailingZeros();
        return value;
    }
}
