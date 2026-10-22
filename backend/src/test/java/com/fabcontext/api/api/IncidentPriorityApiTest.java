package com.fabcontext.api.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.Map;
import java.util.stream.Stream;

import com.fabcontext.api.domain.incident.IncidentRepository;
import com.fabcontext.api.domain.incident.InvestigationPriority;
import com.fabcontext.api.support.ApiTestSupport;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

class IncidentPriorityApiTest extends ApiTestSupport {

	@Autowired IncidentRepository incidents;
	@Autowired TransactionTemplate tx;
	@Autowired JdbcTemplate jdbc;

	@Test
	void legacyNullColumnsReturnUnevaluatedWithoutWritingOrInferringFromSeverity() throws Exception {
		Session user = cmp();
		String id = seededIncident(user, "EV-1009");
		long version = incidentVersion(user, id);
		Map<String, Object> priority = json(getAs(user, "/api/incidents/{id}", id).andExpect(status().isOk()),
			"$.data.investigationPriority");
		assertThat(priority).containsEntry("status", "NOT_EVALUATED").containsEntry("level", null)
			.containsEntry("provisional", false).containsEntry("reason", null);
		getAs(user, "/api/incidents?q=EV-1009")
			.andExpect(jsonPath("$.data.items[0].investigationPriority.status").value("NOT_EVALUATED"));
		assertThat(incidentVersion(user, id)).isEqualTo(version);
		assertThat(jdbc.queryForObject("select investigation_priority_status from incident where incident_id=?", String.class, id)).isNull();
	}

	static Stream<Arguments> savedPriorities() {
		return Stream.of(
			Arguments.of(InvestigationPriority.completed(InvestigationPriority.Level.HIGH, false, "관리한계 이탈")),
			Arguments.of(InvestigationPriority.completed(InvestigationPriority.Level.MEDIUM, false, "관련 센서 동반 편차")),
			Arguments.of(InvestigationPriority.completed(InvestigationPriority.Level.MEDIUM, true, "계측 결과 대기로 중으로 임시 분류했습니다.")),
			Arguments.of(InvestigationPriority.completed(InvestigationPriority.Level.LOW, false, "짧은 단일 편차 후 회복")),
			Arguments.of(InvestigationPriority.pending()),
			Arguments.of(InvestigationPriority.failed("판정 도구 호출 실패")));
	}

	@ParameterizedTest
	@MethodSource("savedPriorities")
	void listAndDetailReturnTheSamePersistedValueWithoutRecalculation(InvestigationPriority priority) throws Exception {
		Session user = cmp();
		String id = newIncident(user, false).incidentId();
		String displayId = json(getAs(user, "/api/incidents/{id}", id), "$.data.displayId");
		String severity = json(getAs(user, "/api/incidents/{id}", id), "$.data.severity");
		tx.executeWithoutResult(status -> {
			var incident = incidents.findById(id).orElseThrow();
			incident.updateInvestigationPriority(priority, Instant.parse("2026-10-22T00:00:00Z"));
			incidents.saveAndFlush(incident);
		});
		long version = incidentVersion(user, id);
		Map<String, Object> detail = json(getAs(user, "/api/incidents/{id}", id)
			.andExpect(status().isOk()).andExpect(jsonPath("$.data.severity").value(severity)), "$.data.investigationPriority");
		Map<String, Object> listed = json(getAs(user, "/api/incidents?q=" + displayId)
			.andExpect(status().isOk()), "$.data.items[0].investigationPriority");
		assertThat(listed).isEqualTo(detail);
		assertThat(detail).containsEntry("status", priority.getStatus().name())
			.containsEntry("level", priority.getLevel() == null ? null : priority.getLevel().name())
			.containsEntry("provisional", priority.isProvisional()).containsEntry("reason", priority.getReason());
		assertThat(incidentVersion(user, id)).isEqualTo(version);
		// 이 사건의 계측은 실제 상 조건이지만 저장된 LOW도 조회 중 다시 계산해 바꾸지 않는다.
		assertThat(jdbc.queryForObject("select investigation_priority_status from incident where incident_id=?", String.class, id))
			.isEqualTo(priority.getStatus().name());
		getAs(cvd(), "/api/incidents/{id}", id).andExpect(status().isForbidden());
	}
}
