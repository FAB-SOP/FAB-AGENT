package com.fabcontext.api.domain.job;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.fabcontext.api.common.ApiException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** 작업별 공개 입력만 전달한다. DB input의 interrupt·내부 플래그는 공개 계약에 섞지 않는다. */
@Component
public class JobInputContract {
    private final String replyFlow;
    public JobInputContract(@Value("${fab.agent.reply-flow:}") String replyFlow) {
        if (!replyFlow.isEmpty() && !List.of("WAIT", "MANUAL").contains(replyFlow)) throw new IllegalArgumentException("fab.agent.reply-flow must be WAIT or MANUAL");
        this.replyFlow = replyFlow;
    }

    public Map<String, Object> initialize(JobKind kind, Map<String, Object> raw, Job parent) {
        Map<String, Object> input = new LinkedHashMap<>(raw == null ? Map.of() : raw);
        if (kind == JobKind.SUMMARY) return input;
        if (input.containsKey("selectedInputs")) {
            input.put("purpose", "FOLLOWUP");
            Map<String, Object> followup = new LinkedHashMap<>();
            followup.put("analysisRequestId", input.get("analysisRequestId"));
            followup.put("question", input.get("followupQuestion"));
            followup.put("selectedInputs", input.get("selectedInputs"));
            input.put("followup", followup);
        }
        if (parent != null) {
            // 기존 thread는 설정 변경 이후에도 생성 당시 흐름을 유지한다. 값이 없는 과거 작업은 Agent 체크포인트에 맡긴다.
            if (parent.getInput().containsKey("replyFlow")) input.put("replyFlow", parent.getInput().get("replyFlow"));
            else input.remove("replyFlow");
            for (String key : List.of("purpose", "followup", "request"))
                if (parent.getInput().containsKey(key)) input.putIfAbsent(key, parent.getInput().get(key));
            return input;
        }
        if (!replyFlow.isEmpty()) input.putIfAbsent("replyFlow", replyFlow);
        String fallback = kind == JobKind.BASIC ? "BASIC" : kind == JobKind.REPORT ? "REPORT"
                : input.containsKey("selectedInputs") ? "FOLLOWUP"
                : "REQUEST".equals(input.get("trigger")) ? "REQUEST" : "QUESTION";
        input.putIfAbsent("purpose", fallback);
        if ("FOLLOWUP".equals(input.get("purpose")) && !input.containsKey("followup")) {
            Map<String, Object> followup = new LinkedHashMap<>();
            followup.put("analysisRequestId", input.get("analysisRequestId"));
            followup.put("question", input.get("followupQuestion"));
            followup.put("selectedInputs", input.get("selectedInputs"));
            input.put("followup", followup);
        }
        validate(kind, input);
        return input;
    }

    public Map<String, Object> publicInput(Job job) {
        if (job.getKind() == JobKind.SUMMARY) return Map.of("summaryRef", job.getInput().get("summaryRef"));
        Map<String, Object> input = new LinkedHashMap<>();
        if (job.getInput().containsKey("replyFlow")) input.put("replyFlow", job.getInput().get("replyFlow"));
        // RESUME의 목적은 저장된 체크포인트가 결정한다.
        if (job.getKind() == JobKind.RESUME) return input;
        for (String key : List.of("purpose", "followup", "request"))
            if (job.getInput().containsKey(key)) input.put(key, job.getInput().get(key));
        if (input.get("followup") instanceof Map<?, ?> followup) {
            Map<String, Object> clean = new LinkedHashMap<>();
            for (String key : List.of("analysisRequestId", "question")) if (followup.containsKey(key)) clean.put(key, followup.get(key));
            if (followup.get("selectedInputs") instanceof List<?> selected) clean.put("selectedInputs", selected.stream().map(value -> {
                Map<String, Object> row = new LinkedHashMap<>();
                var source = (Map<?, ?>) value;
                for (String key : List.of("sourceRef", "evidenceId", "content", "authorUserId", "createdAt", "requestId", "taskId", "evidenceIds"))
                    if (source.containsKey(key)) row.put(key, source.get(key));
                return row;
            }).toList());
            input.put("followup", clean);
        }
        return input;
    }

    private static void validate(JobKind kind, Map<String, Object> input) {
        Object purpose = input.get("purpose");
        boolean valid = switch (kind) {
            case BASIC -> "BASIC".equals(purpose);
            case REPORT -> "REPORT".equals(purpose);
            case DEEP -> List.of("QUESTION", "FOLLOWUP", "REQUEST").contains(purpose);
            default -> true;
        };
        if (!valid) throw ApiException.unprocessable("INVALID_JOB_INPUT", "작업 종류와 목적이 일치하지 않습니다.");
        if (input.containsKey("replyFlow") && !List.of("WAIT", "MANUAL").contains(input.get("replyFlow")))
            throw ApiException.unprocessable("INVALID_JOB_INPUT", "지원하지 않는 회신 흐름입니다.");
        if ("FOLLOWUP".equals(purpose) && (!(input.get("followup") instanceof Map<?, ?> f)
                || !(f.get("analysisRequestId") instanceof String id) || id.isBlank()
                || !(f.get("selectedInputs") instanceof List<?> selected) || selected.isEmpty() || selected.size() > 20))
            throw ApiException.unprocessable("INVALID_JOB_INPUT", "추가 분석의 접수 ID와 선택 결과가 필요합니다.");
    }
}
