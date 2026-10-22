package com.fabcontext.api.domain.approval;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;

import com.fabcontext.api.common.Ref;
import org.junit.jupiter.api.Test;

class ApprovalTest {

	static final Instant T = Instant.parse("2026-10-20T00:00:00Z");

	static Approval pending() {
		return Approval.builder().approvalId("apv_1").incidentId("inc_1").processCode("ETCH")
			.actionType(ApprovalActionType.CHECK_REQUEST).payload(Map.of("question", "q")).createdAt(T)
			.expiresAt(T.plus(Duration.ofHours(24))).source("USER").createdBy("u1").build();
	}

	@Test
	void expiresOnlyAfterDeadlineAndOnlyOnce() {
		Approval a = pending();
		assertThat(a.expireIfDue(T.plus(Duration.ofHours(23)))).isFalse();
		assertThat(a.getStatus()).isEqualTo(ApprovalStatus.PENDING);
		assertThat(a.expireIfDue(T.plus(Duration.ofHours(25)))).isTrue();
		assertThat(a.getStatus()).isEqualTo(ApprovalStatus.EXPIRED);
		assertThat(a.getVersion()).isEqualTo(2);
		assertThat(a.expireIfDue(T.plus(Duration.ofHours(26)))).isFalse();
		assertThat(a.getVersion()).isEqualTo(2);
	}

	@Test
	void decisionsRequirePendingState() {
		Approval a = pending();
		a.approve("u2", "ok", new Ref("CHECK_REQUEST", "chk_1"), "job_2", T);
		assertThat(a.getStatus()).isEqualTo(ApprovalStatus.APPROVED);
		assertThat(a.executionRef()).isEqualTo(new Ref("CHECK_REQUEST", "chk_1"));
		assertThat(a.getResumeJobId()).isEqualTo("job_2");
		assertThatThrownBy(() -> a.reject("u2", "no", T)).hasFieldOrPropertyWithValue("code", "APPROVAL_NOT_PENDING");
		Approval b = pending();
		b.reject("u2", "no", T);
		assertThatThrownBy(() -> b.approve("u2", null, null, null, T)).hasFieldOrPropertyWithValue("code", "APPROVAL_NOT_PENDING");
	}
}
