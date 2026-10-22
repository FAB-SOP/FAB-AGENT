package com.fabcontext.api.domain.checkrequest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;

import org.junit.jupiter.api.Test;

class CheckRequestTest {

	@Test
	void canBeRepliedOnlyOnce() {
		Instant t = Instant.parse("2026-10-20T00:00:00Z");
		CheckRequest r = CheckRequest.builder().requestId("chk_1").incidentId("inc_1").question("q")
			.ownerUserId("u2").requesterUserId("u1").createdAt(t).build();
		assertThat(r.getStatus()).isEqualTo(CheckRequestStatus.OPEN);
		r.decide(true,null,t.plusSeconds(1));
		r.markReplied(t.plusSeconds(5));
		assertThat(r.getStatus()).isEqualTo(CheckRequestStatus.REPLIED);
		assertThat(r.getVersion()).isEqualTo(3);
		assertThat(r.getRepliedAt()).isEqualTo(t.plusSeconds(5));
		assertThatThrownBy(() -> r.markReplied(t)).hasFieldOrPropertyWithValue("code", "REQUEST_NOT_ACCEPTED");
	}
}
