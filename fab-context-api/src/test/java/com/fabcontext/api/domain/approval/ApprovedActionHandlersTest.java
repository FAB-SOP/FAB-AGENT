package com.fabcontext.api.domain.approval;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Arrays;
import java.util.List;
import java.util.Map;

import com.fabcontext.api.common.Ref;
import com.fabcontext.api.domain.incident.Incident;
import org.junit.jupiter.api.Test;

/** 승인 종류마다 실행기가 꼭 하나 — 빠지거나 겹치면 서버가 뜨지 않는다 */
class ApprovedActionHandlersTest {

	record Stub(ApprovalActionType type) implements ApprovedActionHandler {

		@Override
		public Map<String, Object> normalize(Map<String, Object> raw, Incident incident) {
			return raw;
		}

		@Override
		public Ref execute(Approval approval, Incident incident, String actorId, String threadId) {
			return new Ref(type.name(), "x");
		}
	}

	static List<ApprovedActionHandler> all() {
		return Arrays.stream(ApprovalActionType.values()).<ApprovedActionHandler>map(Stub::new).toList();
	}

	@Test
	void picksHandlerByType() {
		ApprovedActionHandlers h = new ApprovedActionHandlers(all());
		for (ApprovalActionType t : ApprovalActionType.values()) {
			assertThat(h.of(t).type()).isEqualTo(t);
		}
	}

	@Test
	void missingOrDuplicateHandlerFailsStartup() {
		assertThatThrownBy(() -> new ApprovedActionHandlers(all().subList(1, all().size())))
			.hasMessageContaining("실행기가 없습니다");
		List<ApprovedActionHandler> twice = new java.util.ArrayList<>(all());
		twice.add(new Stub(ApprovalActionType.CHECK_REQUEST));
		assertThatThrownBy(() -> new ApprovedActionHandlers(twice)).hasMessageContaining("실행기가 둘입니다");
	}
}
