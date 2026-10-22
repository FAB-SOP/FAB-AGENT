package com.fabcontext.api.domain.incident;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.List;

import com.fabcontext.api.common.ApiException;
import org.junit.jupiter.api.Test;

class IncidentTest {

	static Incident incident() {
		Instant t = Instant.parse("2026-10-20T00:00:00Z");
		return Incident.builder().incidentId("inc_1").displaySeq(1024).title("t").processCode("ETCH")
			.equipmentId("ETCH-02").waferIds(List.of("W07")).detectedAt(t).createdAt(t).build();
	}

	@Test
	void newIncidentStartsOpenAtVersionOne() {
		Incident i = incident();
		assertThat(i.getDisplayId()).isEqualTo("EV-1024");
		assertThat(i.getStatus()).isEqualTo(IncidentStatus.OPEN);
		assertThat(i.getVersion()).isEqualTo(1);
		assertThat(i.isActive()).isTrue();
	}

	@Test
	void recordAppendsTimelineAndBumpsVersion() {
		Incident i = incident();
		Instant at = Instant.parse("2026-10-20T01:00:00Z");
		i.record(at, "DETECTED", "svc", "m", "EVENT", "e1");
		assertThat(i.getVersion()).isEqualTo(2);
		assertThat(i.getUpdatedAt()).isEqualTo(at);
		assertThat(i.getTimeline()).singleElement().satisfies(e -> {
			assertThat(e.getType()).isEqualTo("DETECTED");
			assertThat(e.getRefId()).isEqualTo("e1");
		});
	}

	@Test
	void versionAndClosedGuards() {
		Incident i = incident();
		i.checkVersion(1L);
		assertThatThrownBy(() -> i.checkVersion(0L)).hasFieldOrPropertyWithValue("code", "VERSION_CONFLICT");
		i.setStatus(IncidentStatus.CLOSED);
		assertThat(i.isActive()).isFalse();
		assertThatThrownBy(i::requireNotClosed).isInstanceOf(ApiException.class)
			.hasFieldOrPropertyWithValue("code", "INCIDENT_CLOSED");
	}

	@Test
	void csvFilterParsing() {
		assertThat(IncidentService.csv(" closed, on_hold ,")).containsExactlyInAnyOrder("CLOSED", "ON_HOLD");
		assertThat(IncidentService.csv(null)).isEmpty();
	}
}
