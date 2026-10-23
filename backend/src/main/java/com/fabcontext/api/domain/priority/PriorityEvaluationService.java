package com.fabcontext.api.domain.priority;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import com.fabcontext.api.common.AfterCommit;
import com.fabcontext.api.common.stream.IncidentStreamHub;
import com.fabcontext.api.domain.incident.Incident;
import com.fabcontext.api.domain.incident.IncidentDtos.InvestigationPriorityView;
import com.fabcontext.api.domain.incident.IncidentRepository;
import com.fabcontext.api.domain.incident.InvestigationPriority;
import com.fabcontext.api.domain.priority.PriorityEvaluationCalculator.Input;
import com.fabcontext.api.domain.priority.PriorityEvaluationCalculator.Outcome;
import com.fabcontext.api.domain.processdata.PackStore;
import com.fabcontext.api.domain.processdata.PriorityEvidencePolicy;
import jakarta.persistence.EntityManager;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Service
@RequiredArgsConstructor
public class PriorityEvaluationService {
    private final PriorityEvaluationRepository evaluations;
    private final IncidentRepository incidents;
    private final EntityManager entityManager;
    private final PriorityEvidencePolicy policy;
    private final PackStore pack;
    private final IncidentStreamHub stream;
    private final Clock clock;

    /** 사건 접수 트랜잭션 안에서 최초 평가를 한 번만 저장한다. 기존 사건·재분석은 호출하지 않는다. */
    @Transactional
    public void enqueue(Incident incident) {
        if (!policy.enabled() || evaluations.existsById(incident.getIncidentId())) return;
        Instant now = clock.instant();
        Instant asOf = now.isBefore(pack.analysisAsOf()) ? now : pack.analysisAsOf();
        List<String> runs = incident.getRunIds().stream().map(id -> id == null ? "" : id).distinct().toList();
        PriorityEvaluation evaluation = evaluations.save(new PriorityEvaluation(incident.getIncidentId(), policy.version(), asOf, runs, now));
        incident.updateInvestigationPriority(InvestigationPriority.pending(), now);
        publish(incident);
        logStatus(evaluation);
    }

    @Transactional
    public Optional<Input> claimNext() {
        if (!policy.enabled()) return Optional.empty();
        Instant now = clock.instant();
        var rows = evaluations.runnable(now, PageRequest.of(0, 1));
        if (rows.isEmpty()) return Optional.empty();
        PriorityEvaluation evaluation = rows.getFirst();
        if (evaluation.getAttempt() >= PriorityEvaluation.MAX_ATTEMPTS) {
            evaluation.fail("RETRY_EXHAUSTED", evaluation.getDetails(), false, now);
            savePriority(evaluation.getIncidentId(), InvestigationPriority.failed("우선순위 평가가 제한 횟수 안에 완료되지 않았습니다."));
            logStatus(evaluation);
            return Optional.empty();
        }
        evaluation.claim(now);
        Incident incident = incidents.findById(evaluation.getIncidentId()).orElseThrow();
        logStatus(evaluation);
        return Optional.of(new Input(incident.getIncidentId(), evaluation.getAttempt(), evaluation.getPolicyVersion(),
                evaluation.getAssessmentAsOf(), List.copyOf(evaluation.getRunIds()), incident.getProcessCode(),
                incident.getEquipmentId(), incident.getLotId(), List.copyOf(incident.getWaferIds())));
    }

    @Transactional
    public void complete(Input input, Outcome outcome) {
        PriorityEvaluation evaluation = evaluations.locked(input.incidentId()).orElseThrow();
        if (!evaluation.matches(input.attempt())) return; // 만료된 이전 실행·중복 완료는 무시
        if (outcome.priority().getStatus() == InvestigationPriority.Status.COMPLETED) {
            evaluation.complete(outcome.details(), clock.instant());
        } else {
            evaluation.fail(outcome.errorCode(), outcome.details(), false, clock.instant());
        }
        savePriority(input.incidentId(), outcome.priority());
        logStatus(evaluation);
    }

    @Transactional
    public void fail(Input input, String errorCode, boolean retryable) {
        PriorityEvaluation evaluation = evaluations.locked(input.incidentId()).orElseThrow();
        if (!evaluation.matches(input.attempt())) return;
        evaluation.fail(errorCode, Map.of("errorCode", errorCode, "assessmentAsOf", input.asOf().toString(),
                "policyVersion", input.policyVersion()), retryable, clock.instant());
        if (evaluation.getStatus() == PriorityEvaluation.Status.FAILED) {
            savePriority(input.incidentId(), InvestigationPriority.failed("우선순위 평가 중 오류가 발생했습니다. 오류 코드: " + errorCode));
        }
        logStatus(evaluation);
    }

    /** 우선순위는 독립 메타데이터다. AI와 경쟁하는 사건 workflow/ORM 버전을 올리지 않는다.
     * Incident의 DynamicUpdate가 다른 실행의 오래된 우선순위 값 덮어쓰기도 막는다. */
    private void savePriority(String id, InvestigationPriority priority) {
        entityManager.createNativeQuery("""
            update {h-schema}incident set investigation_priority_status=:status, investigation_priority_level=:level,
                investigation_priority_provisional=:provisional, investigation_priority_reason=:reason
            where incident_id=:id
            """)
                .setParameter("status", priority.getStatus().name())
                .setParameter("level", priority.getLevel() == null ? null : priority.getLevel().name())
                .setParameter("provisional", priority.isProvisional()).setParameter("reason", priority.getReason())
                .setParameter("id", id).executeUpdate();
        Incident incident = incidents.findById(id).orElseThrow();
        stream.publish(id, "incident.updated", Map.of("version", incident.getVersion(),
                "investigationPriority", new InvestigationPriorityView(priority.getStatus(), priority.getLevel(), priority.isProvisional(), priority.getReason())));
    }

    private void publish(Incident incident) {
        var p = incident.getInvestigationPriority();
        stream.publish(incident.getIncidentId(), "incident.updated", Map.of("version", incident.getVersion(),
                "investigationPriority", new InvestigationPriorityView(p.getStatus(), p.getLevel(), p.isProvisional(), p.getReason())));
    }

    private void logStatus(PriorityEvaluation e) {
        String id = e.getIncidentId(), version = e.getPolicyVersion(), status = e.getStatus().name(), code = e.getErrorCode();
        int attempt = e.getAttempt();
        AfterCommit.run(() -> {
            if ("FAILED".equals(status)) log.error("event=priority.evaluation incidentId={} policyVersion={} attempt={} status={} errorCode={}", id, version, attempt, status, code);
            else log.info("event=priority.evaluation incidentId={} policyVersion={} attempt={} status={} errorCode={}", id, version, attempt, status, code);
        });
    }
}
