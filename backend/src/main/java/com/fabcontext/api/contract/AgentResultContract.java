package com.fabcontext.api.contract;

import java.util.List;
import java.util.Map;
import java.util.Set;

import com.fabcontext.api.common.ApiException;
import org.springframework.http.HttpStatus;

/** 신규 v2 칸은 검증하고 기존 결과도 계속 받는다. 범용 스키마 의존성은 추가하지 않는다. */
public final class AgentResultContract {
    private AgentResultContract() {}
    private static final Set<String> NEXT_STEPS = Set.of("CURRENT_CHECK", "UPSTREAM_CHECK", "OBSERVE", "INSUFFICIENT_EVIDENCE", "NEEDS_DEEP_ANALYSIS");
    private static final Set<String> SOURCES = Set.of("SENSOR", "METROLOGY", "ROUTE", "CHANGE", "REPLY", "DOCUMENT", "ACTION");

    public static void validate(Map<String, Object> result, Map<String, Object> analysis, String jobId, String incidentId) {
        if (result == null) throw invalid("payload.result", "분석 결과 객체가 필요합니다.");
        if (result.containsKey("jobId") && !jobId.equals(result.get("jobId"))) throw invalid("payload.result.jobId", "현재 작업과 다릅니다.");
        if (result.containsKey("incidentId") && !incidentId.equals(result.get("incidentId"))) throw invalid("payload.result.incidentId", "현재 사건과 다릅니다.");
        text(result, "resultId", 255, "payload.result");
        text(result, "answer", 12000, "payload.result");
        for (String key : List.of("observedFacts", "candidateCauses", "evidenceRefs")) {
            List<Map<String, Object>> rows = objects(result.getOrDefault(key, List.of()), "payload.result." + key);
            for (Map<String, Object> row : rows) {
                if (key.equals("candidateCauses")) {
                    text(row, "candidateId", 255, "payload.result.candidateCauses");
                    enumeration(row, "stance", Set.of("SUPPORTED", "REFUTED", "UNDETERMINED"), "payload.result.candidateCauses");
                    strings(row.getOrDefault("causeIds", List.of()), "payload.result.candidateCauses.causeIds");
                    for (String field : List.of("supportingEvidenceIds", "conflictingEvidenceIds", "missingData"))
                        strings(row.getOrDefault(field, List.of()), "payload.result.candidateCauses." + field);
                    text(row, "hypothesis", 4000, "payload.result.candidateCauses");
                    text(row, "nextCheck", 4000, "payload.result.candidateCauses");
                    if (row.containsKey("priority") && (!(row.get("priority") instanceof Number n) || n.intValue() < 1 || n.doubleValue() != n.intValue()))
                        throw invalid("payload.result.candidateCauses.priority", "양의 정수가 필요합니다.");
                } else if (key.equals("observedFacts")) {
                    text(row, "text", 12000, "payload.result.observedFacts");
                    strings(row.getOrDefault("evidenceIds", List.of()), "payload.result.observedFacts.evidenceIds");
                } else {
                    enumeration(row, "sourceType", SOURCES, "payload.result.evidenceRefs");
                    text(row, "evidenceId", 255, "payload.result.evidenceRefs");
                    text(row, "sourceId", 255, "payload.result.evidenceRefs");
                }
            }
        }
        strings(result.getOrDefault("limitations", List.of()), "payload.result.limitations");
        enumeration(analysis, "resultKind", Set.of("CAUSE_ANALYSIS", "ANSWER"), "payload.analysis");
        enumeration(analysis, "nextStep", NEXT_STEPS, "payload.analysis");
        if (analysis.containsKey("conclusion") && !(analysis.get("conclusion") instanceof Map<?, ?>))
            throw invalid("payload.analysis.conclusion", "객체가 필요합니다.");
        if (analysis.containsKey("conclusion")) {
            var c = (Map<?, ?>) analysis.get("conclusion");
            for (String key : List.of("changed", "adjusted")) if (c.containsKey(key) && !(c.get(key) instanceof Boolean))
                throw invalid("payload.analysis.conclusion." + key, "참/거짓이 필요합니다.");
        }
    }

    @SuppressWarnings("unchecked")
    public static Map<String, Object> object(Object value, String path) {
        if (!(value instanceof Map<?, ?> map)) throw invalid(path, "객체가 필요합니다.");
        return (Map<String, Object>) map;
    }
    @SuppressWarnings("unchecked")
    public static List<Map<String, Object>> objects(Object value, String path) {
        if (!(value instanceof List<?> list) || list.size() > 200 || list.stream().anyMatch(v -> !(v instanceof Map<?, ?>)))
            throw invalid(path, "객체 배열(최대 200개)이 필요합니다.");
        return (List<Map<String, Object>>) value;
    }
    private static void strings(Object value, String path) {
        if (!(value instanceof List<?> list) || list.size() > 200 || list.stream().anyMatch(v -> !(v instanceof String)))
            throw invalid(path, "문자열 배열이 필요합니다.");
    }
    private static void text(Map<String, Object> map, String key, int max, String path) {
        if (map.containsKey(key) && (!(map.get(key) instanceof String s) || s.length() > max)) throw invalid(path + "." + key, "문자열(최대 " + max + "자)이 필요합니다.");
    }
    private static void enumeration(Map<String, Object> map, String key, Set<String> values, String path) {
        if (map.containsKey(key) && (!(map.get(key) instanceof String v) || !values.contains(v))) throw invalid(path + "." + key, "허용값: " + String.join(", ", values));
    }
    public static ApiException invalid(String field, String message) {
        return new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "INVALID_AGENT_RESULT", "Agent 결과 계약을 확인하세요.",
                Map.of("errors", List.of(Map.of("field", field, "message", message))));
    }
}
