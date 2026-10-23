package com.fabcontext.api.domain.priority;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import com.fabcontext.api.domain.incident.InvestigationPriority;
import com.fabcontext.api.domain.processdata.InvestigationPriorityRules.Assessment;
import com.fabcontext.api.domain.processdata.ProcessDataRepositories.RouteRunRepository;
import com.fabcontext.api.domain.processdata.RouteRun;
import com.fabcontext.api.internal.query.InternalPriorityEvidenceQuery;
import org.junit.jupiter.api.Test;

class PriorityEvaluationCalculatorTest {
    private final RouteRunRepository routes = mock(RouteRunRepository.class);
    private final InternalPriorityEvidenceQuery evidence = mock(InternalPriorityEvidenceQuery.class);
    private final PriorityEvaluationCalculator calculator = new PriorityEvaluationCalculator(routes, evidence);
    private final Instant asOf = Instant.parse("2026-10-20T02:59:00Z");

    private PriorityEvaluationCalculator.Input input(String... ids) {
        return new PriorityEvaluationCalculator.Input("inc", 1, "policy", asOf, List.of(ids), "CMP", "CMP-PM3", "L1", List.of("W1", "W2"));
    }
    private void run(String id, String process, String equipment, String level, boolean provisional) {
        var route = RouteRun.builder().runId(id).processCode(process).equipmentId(equipment).lotId("L1").waferId("W1").phase("CURRENT").build();
        when(routes.findByRunId(id)).thenReturn(Optional.of(route));
        when(evidence.evidence(eq(id), eq(asOf), eq("policy"))).thenReturn(Map.of("data", Map.of("assessment",
                new Assessment("COMPLETED", level, provisional, List.of("RULE"), "판정 근거", List.of("ev"), List.of(), "policy", asOf.toString(), "DEMO_ACTIVE"))));
    }

    @Test void highestValidRunWinsAndKeepsProvenance() {
        run("low", "CMP", "CMP-PM3", "LOW", false);
        run("high", "CMP", "CMP-PM3", "HIGH", false);
        var result = calculator.calculate(input("low", "high"));
        assertThat(result.priority().getLevel()).isEqualTo(InvestigationPriority.Level.HIGH);
        assertThat(result.details()).containsEntry("policyVersion", "policy").containsEntry("assessmentAsOf", asOf.toString());
        assertThat((List<?>) result.details().get("runAssessments")).hasSize(2);
    }

    @Test void missingEvidenceMediumIsSuccessfulButTechnicalRunFailureIsNotMadeMedium() {
        run("pending", "CMP", "CMP-PM3", "MEDIUM", true);
        var result = calculator.calculate(input("pending"));
        assertThat(result.priority().getStatus()).isEqualTo(InvestigationPriority.Status.COMPLETED);
        assertThat(result.priority().isProvisional()).isTrue();
        when(routes.findByRunId("unknown")).thenReturn(Optional.empty());
        var failed = calculator.calculate(input("pending", "unknown"));
        assertThat(failed.priority().getStatus()).isEqualTo(InvestigationPriority.Status.FAILED);
        assertThat(failed.priority().getLevel()).isNull();
    }

    @Test void highSurvivesAnotherRunFailureButUnrelatedEquipmentCannotGiveHigh() {
        run("high", "CMP", "CMP-PM3", "HIGH", false);
        when(routes.findByRunId("unknown")).thenReturn(Optional.empty());
        var result = calculator.calculate(input("high", "unknown"));
        assertThat(result.priority().getLevel()).isEqualTo(InvestigationPriority.Level.HIGH);
        assertThat(result.details()).containsEntry("partialFailure", true);
        run("wrong", "CMP", "CMP-PM1", "HIGH", false);
        assertThat(calculator.calculate(input("wrong")).priority().getStatus()).isEqualTo(InvestigationPriority.Status.FAILED);
        verify(evidence, never()).evidence(eq("wrong"), any(), any());
    }

    @Test void lineageIdsFromOtherProcessDoNotDetermineIncidentRunPriority() {
        run("etch", "ETCH", "ETCH-PM2", "HIGH", false);
        run("cmp", "CMP", "CMP-PM3", "LOW", false);
        assertThat(calculator.calculate(input("etch", "cmp")).priority().getLevel()).isEqualTo(InvestigationPriority.Level.LOW);
        verify(evidence, never()).evidence(eq("etch"), any(), any());
    }

    @Test void noExplicitRunResolvesWaferScopeWithoutInventingIds() {
        run("cmp", "CMP", "CMP-PM3", "LOW", false);
        RouteRun target = routes.findByRunId("cmp").orElseThrow();
        when(routes.findByWaferIdInOrderByWaferIdAscSequenceNoAsc(List.of("W1", "W2")))
                .thenReturn(List.of(target));
        assertThat(calculator.calculate(input()).priority().getLevel()).isEqualTo(InvestigationPriority.Level.LOW);
    }
}
