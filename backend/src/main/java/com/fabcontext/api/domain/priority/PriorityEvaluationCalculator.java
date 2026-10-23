package com.fabcontext.api.domain.priority;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.fabcontext.api.common.Json;
import com.fabcontext.api.domain.incident.InvestigationPriority;
import com.fabcontext.api.domain.processdata.InvestigationPriorityRules.Assessment;
import com.fabcontext.api.domain.processdata.ProcessDataRepositories.RouteRunRepository;
import com.fabcontext.api.domain.processdata.RouteRun;
import com.fabcontext.api.internal.query.InternalPriorityEvidenceQuery;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.TransientDataAccessException;
import org.springframework.stereotype.Component;
import tools.jackson.core.type.TypeReference;

/** 기존 계산 서비스를 직접 호출한다. Agent·HTTP 자기 호출·탐지 severity 추정은 사용하지 않는다. */
@Component
@RequiredArgsConstructor
public class PriorityEvaluationCalculator {
    private final RouteRunRepository routes;
    private final InternalPriorityEvidenceQuery evidence;

    public record Input(String incidentId, int attempt, String policyVersion, Instant asOf, List<String> runIds,
            String processCode, String equipmentId, String lotId, List<String> waferIds) {}
    public record Outcome(InvestigationPriority priority, Map<String, Object> details, String errorCode) {}

    @SuppressWarnings("unchecked")
    public Outcome calculate(Input input) {
        List<String> targetIds = input.runIds();
        if (targetIds.isEmpty() && !input.waferIds().isEmpty()) {
            targetIds = routes.findByWaferIdInOrderByWaferIdAscSequenceNoAsc(input.waferIds()).stream()
                    .filter(run -> sameScope(input, run)).map(RouteRun::getRunId).distinct().toList();
        }
        List<Assessment> valid = new ArrayList<>();
        List<Map<String, Object>> perRun = new ArrayList<>();
        boolean failed = targetIds.isEmpty();
        for (String runId : targetIds) {
            RouteRun run = routes.findByRunId(runId).orElse(null);
            if (run == null) {
                perRun.add(Map.of("runId", runId, "status", "FAILED", "errorCode", "RUN_NOT_FOUND"));
                failed = true;
                continue;
            }
            // runIds에 경로 전체가 들어와도 사건 공정/설비 run만 평가한다.
            if (!input.processCode().equals(run.getProcessCode())) continue;
            if (!sameScope(input, run)) {
                perRun.add(Map.of("runId", runId, "status", "FAILED", "errorCode", "RUN_SCOPE_MISMATCH"));
                failed = true;
                continue;
            }
            try {
                Map<String, Object> envelope = evidence.evidence(runId, input.asOf(), input.policyVersion());
                Assessment result = (Assessment) ((Map<String, Object>) envelope.get("data")).get("assessment");
                valid.add(result);
                perRun.add(Map.of("runId", runId, "status", "COMPLETED", "assessment", result));
            } catch (TransientDataAccessException | DataAccessResourceFailureException ex) {
                throw ex;
            } catch (RuntimeException ex) {
                perRun.add(Map.of("runId", runId, "status", "FAILED", "errorCode", "EVIDENCE_UNAVAILABLE"));
                failed = true;
            }
        }
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("policyVersion", input.policyVersion());
        details.put("assessmentAsOf", input.asOf().toString());
        details.put("runAssessments", perRun);
        details.put("partialFailure", failed);
        // JSONB에는 직렬화 가능한 일반 Map/List만 저장한다.
        details = Json.read(Json.write(details), new TypeReference<>() {});
        Assessment highest = valid.stream().min(Comparator.comparingInt(a ->
                InvestigationPriority.Level.valueOf(a.level()).ordinal())).orElse(null);
        // 상은 이미 최고 등급이므로 다른 run 실패로 낮추지 않는다.
        // 그 외에는 평가하지 못한 run을 정상/중/하로 추정하지 않는다.
        if (highest == null || (failed && !"HIGH".equals(highest.level()))) {
            String code = targetIds.isEmpty() ? "EVALUATION_TARGET_MISSING" : "RUN_EVALUATION_FAILED";
            return new Outcome(InvestigationPriority.failed("우선순위 평가에 필요한 run을 확인하거나 평가하지 못했습니다. 오류 코드: " + code), details, code);
        }
        String reason = highest.reason();
        if (targetIds.size() > 1) reason = "사건 대상 run 중 가장 높은 판정입니다. " + reason;
        if (failed) reason += " 일부 run 평가에는 실패했지만 확보한 유효 상 판정은 유지합니다.";
        if (reason.length() > 2000) reason = reason.substring(0, 1999) + "…";
        return new Outcome(InvestigationPriority.completed(InvestigationPriority.Level.valueOf(highest.level()),
                highest.provisional(), reason), details, null);
    }

    private static boolean sameScope(Input input, RouteRun run) {
        return input.processCode().equals(run.getProcessCode()) && input.equipmentId().equals(run.getEquipmentId())
                && (input.lotId() == null || input.lotId().equals(run.getLotId()))
                && (input.waferIds().isEmpty() || input.waferIds().contains(run.getWaferId()))
                && !"FOLLOWUP".equals(run.getPhase());
    }
}
