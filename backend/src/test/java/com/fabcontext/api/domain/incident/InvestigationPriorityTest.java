package com.fabcontext.api.domain.incident;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

class InvestigationPriorityTest {

	@ParameterizedTest
	@EnumSource(InvestigationPriority.Level.class)
	void completedAssessmentPreservesItsLevel(InvestigationPriority.Level level) {
		var priority = InvestigationPriority.completed(level, false, "저장된 판정 근거");
		assertThat(priority.getStatus()).isEqualTo(InvestigationPriority.Status.COMPLETED);
		assertThat(priority.getLevel()).isEqualTo(level);
		assertThat(priority.isProvisional()).isFalse();
	}

	@Test
	void insufficientEvidenceIsCompletedProvisionalMedium() {
		var priority = InvestigationPriority.completed(InvestigationPriority.Level.MEDIUM, true, "계측 대기");
		assertThat(priority.getStatus()).isEqualTo(InvestigationPriority.Status.COMPLETED);
		assertThat(priority.isProvisional()).isTrue();
		assertThatThrownBy(() -> InvestigationPriority.completed(InvestigationPriority.Level.LOW, true, "근거 부족"))
			.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> InvestigationPriority.completed(InvestigationPriority.Level.HIGH, true, "근거 부족"))
			.isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	void unevaluatedPendingAndFailedDoNotInventALevel() {
		for (var priority : java.util.List.of(InvestigationPriority.notEvaluated(), InvestigationPriority.pending(),
				InvestigationPriority.failed("판정 도구 호출 실패"))) {
			assertThat(priority.getLevel()).isNull();
			assertThat(priority.isProvisional()).isFalse();
		}
	}

	@Test
	void completedAndFailedRequireMeaningfulReasons() {
		assertThatThrownBy(() -> InvestigationPriority.completed(null, false, "근거"))
			.isInstanceOf(NullPointerException.class);
		assertThatThrownBy(() -> InvestigationPriority.completed(InvestigationPriority.Level.LOW, false, " "))
			.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> InvestigationPriority.failed(null)).isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> InvestigationPriority.failed("x".repeat(2001))).isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	void priorityReadDoesNotChangeIncidentAndUpdateDoesNotChangeInvestigationState() {
		Incident incident = IncidentTest.incident();
		incident.setStatus(IncidentStatus.WAITING_REPLY);
		incident.setActiveJobId("waiting-job");
		incident.setLatestJobStatus("WAITING_REPLY");
		long version = incident.getVersion();
		assertThat(incident.getInvestigationPriority().getStatus()).isEqualTo(InvestigationPriority.Status.NOT_EVALUATED);
		assertThat(incident.getVersion()).isEqualTo(version);
		Instant at = Instant.parse("2026-10-20T02:00:00Z");
		incident.updateInvestigationPriority(InvestigationPriority.completed(InvestigationPriority.Level.MEDIUM, true, "계측 대기"), at);
		assertThat(incident.getVersion()).isEqualTo(version + 1);
		assertThat(incident.getUpdatedAt()).isEqualTo(at);
		assertThat(incident.getStatus()).isEqualTo(IncidentStatus.WAITING_REPLY);
		assertThat(incident.getActiveJobId()).isEqualTo("waiting-job");
		assertThat(incident.getLatestJobStatus()).isEqualTo("WAITING_REPLY");
		assertThat(incident.getTimeline()).isEmpty();
	}
}
