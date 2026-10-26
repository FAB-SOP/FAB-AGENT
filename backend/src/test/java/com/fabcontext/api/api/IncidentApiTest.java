package com.fabcontext.api.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;

import com.fabcontext.api.support.ApiTestSupport;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

@DisplayName("P03 사건 목록 · 상세 · 관리자 상태 전이")
class IncidentApiTest extends ApiTestSupport {

	@Autowired
	JdbcTemplate jdbc;

	@Test
	void listIsScopedToProcessAndSortedNewestFirst() throws Exception {
		Session etch = cmp();
		getAs(etch, "/api/incidents?size=100")
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.items[*].processCode", everyItem(is("CMP"))))
			.andExpect(jsonPath("$.data.items[*].displayId", hasItems("EV-1006", "EV-1009", "EV-1021")))
			.andExpect(jsonPath("$.data.items[*].displayId", not(hasItem("EV-1023"))));
		getAs(cvd(), "/api/incidents?size=100")
			.andExpect(jsonPath("$.data.items[*].processCode", everyItem(is("CVD"))));
		getAs(manager(), "/api/incidents?size=100")
			.andExpect(jsonPath("$.data.items[*].processCode", hasItems("CVD", "CMP")));
	}

	@Test
	void filtersBySearchStatusAndJobStatus() throws Exception {
		Session etch = cmp();
		getAs(etch, "/api/incidents?q=w-current-006&size=100").andExpect(jsonPath("$.data.items[*].displayId", hasItem("EV-1006")));
		getAs(etch, "/api/incidents?q=cmp-pm2&size=100").andExpect(jsonPath("$.data.items[*].equipmentId", everyItem(is("CMP-PM2"))));
		getAs(etch, "/api/incidents?status=CLOSED&size=100").andExpect(jsonPath("$.data.items[*].status", everyItem(is("CLOSED"))));
		getAs(etch, "/api/incidents?status=on_hold,closed&size=100")
			.andExpect(jsonPath("$.data.items[*].status", everyItem(oneOf("ON_HOLD", "CLOSED"))));
		getAs(etch, "/api/incidents?jobStatus=SUCCEEDED&size=100")
			.andExpect(jsonPath("$.data.items[*].jobStatus", everyItem(is("SUCCEEDED"))));
		getAs(etch, "/api/incidents?q=no-such-thing").andExpect(jsonPath("$.data.items", hasSize(0)))
			.andExpect(jsonPath("$.data.nextCursor").doesNotExist());
	}

	@Test
	void cursorPaginationWalksAllItemsWithoutDuplicates() throws Exception {
		Session etch = cmp();
		long total = ((java.util.List<?>) json(getAs(etch, "/api/incidents?size=100"), "$.data.items")).size();
		java.util.Set<String> seen = new java.util.HashSet<>();
		String cursor = "";
		do {
			var page = getAs(etch, "/api/incidents?size=2" + (cursor.isEmpty() ? "" : "&cursor=" + cursor))
				.andExpect(status().isOk());
			java.util.List<String> ids = json(page, "$.data.items[*].incidentId");
			assertThat(ids.size()).isLessThanOrEqualTo(2);
			ids.forEach(id -> assertThat(seen.add(id)).as("duplicate " + id).isTrue());
			cursor = json(page, "$.data.nextCursor");
		}
		while (cursor != null);
		assertThat(seen).hasSize((int) total);
	}

	@Test
	void invalidPagingAndScopeAreRejected() throws Exception {
		Session etch = cmp();
		getAs(etch, "/api/incidents?cursor=%%%").andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.error.code").value("INVALID_CURSOR"));
		getAs(etch, "/api/incidents?size=0").andExpect(status().isBadRequest());
		getAs(etch, "/api/incidents?size=101").andExpect(jsonPath("$.error.code").value("INVALID_PAGE_SIZE"));
		getAs(etch, "/api/incidents?size=abc").andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.error.code").value("INVALID_PARAMETER"));
		getAs(etch, "/api/incidents?processCode=CVD").andExpect(status().isForbidden())
			.andExpect(jsonPath("$.error.code").value("PROCESS_SCOPE_DENIED"));
	}

	@Test
	void detailHasContextTimelineAndVersion() throws Exception {
		Session etch = cmp();
		String id = seededIncident(etch, "EV-1021");
		getAs(etch, "/api/incidents/{id}", id)
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.displayId").value("EV-1021"))
			.andExpect(jsonPath("$.data.status").value("CLOSED"))
			.andExpect(jsonPath("$.data.version").value(greaterThan(1)))
			.andExpect(jsonPath("$.data.timeline[0].type").value("DETECTED"))
			.andExpect(jsonPath("$.data.timeline[*].type", hasItems("ACTION_RECORDED", "ACTION_VERIFIED",
				"REPORT_CONFIRMED", "STATUS_CHANGED")));
		getAs(etch, "/api/incidents/{id}", "inc_missing").andExpect(status().isNotFound())
			.andExpect(jsonPath("$.error.code").value("INCIDENT_NOT_FOUND"));
		getAs(cvd(), "/api/incidents/{id}", id).andExpect(status().isForbidden());
	}

	@Test
	void managerTransitionsAreRemoved() throws Exception {
		Session etch = cmp();
		Session mgr = manager();
		String id = newIncident(etch, false).incidentId();
		long v = incidentVersion(etch, id);
		String body = "{\"targetStatus\":\"%s\",\"reason\":\"%s\",\"expectedVersion\":%d}";

		// 권한 · 버전 · 입력 검사는 그대로 먼저
		postAs(etch, "/api/incidents/{id}/transitions", body.formatted("ON_HOLD", "r", v), id)
			.andExpect(status().isForbidden()).andExpect(jsonPath("$.error.code").value("MANAGER_ROLE_REQUIRED"));
		postAs(mgr, "/api/incidents/{id}/transitions", body.formatted("ON_HOLD", "r", v - 1), id)
			.andExpect(status().isConflict()).andExpect(jsonPath("$.error.details.currentVersion").value((int) v));
		postAs(mgr, "/api/incidents/{id}/transitions", "{\"targetStatus\":\"ON_HOLD\",\"expectedVersion\":" + v + "}", id)
			.andExpect(status().isUnprocessableEntity());

		// 사건 종료는 보고서 확정으로만(003 · 헌법 3.0.0) — 관리자의 보류 · 보류 해제 · 종료는 모두 없다
		for (String target : List.of("ON_HOLD", "CLOSED", "INVESTIGATING", "OPEN")) {
			postAs(mgr, "/api/incidents/{id}/transitions", body.formatted(target, "r", v), id)
				.andExpect(status().isConflict()).andExpect(jsonPath("$.error.code").value("TRANSITION_REMOVED"));
		}
		getAs(etch, "/api/incidents/{id}", id).andExpect(jsonPath("$.data.status").value(not(is("CLOSED"))));
	}

	@Test
	void legacyOnHoldIncidentClosesOnlyByReportConfirmation() throws Exception {
		Session etch = cmp();
		Session mgr = manager();
		String id = newIncident(etch, false).incidentId();
		// 도입 전에 보류된 사건(이력 데이터)
		jdbc.update("update incident set status = 'ON_HOLD' where incident_id = ?", id);
		long v = incidentVersion(etch, id);
		postAs(mgr, "/api/incidents/{id}/transitions", "{\"targetStatus\":\"INVESTIGATING\",\"reason\":\"재개\",\"expectedVersion\":" + v + "}", id)
			.andExpect(status().isConflict()).andExpect(jsonPath("$.error.code").value("TRANSITION_REMOVED"));

		String jobId = json(postAs(etch, "/api/incidents/{id}/report-jobs", "{\"expectedIncidentVersion\":" + v + "}", id)
			.andExpect(status().isAccepted()), "$.data.jobId");
		awaitJob(etch, jobId, "SUCCEEDED");
		String reportId = json(getAs(etch, "/api/jobs/{id}", jobId), "$.data.resultRef.id");
		postAs(etch, "/api/reports/{id}/confirmations",
				"{\"expectedContentRevision\":1,\"reviewConfirmed\":true,\"classification\":{\"category\":\"HOLD\"}}", reportId)
			.andExpect(status().isOk());
		getAs(etch, "/api/incidents/{id}", id).andExpect(jsonPath("$.data.status").value("CLOSED"))
			.andExpect(jsonPath("$.data.timeline[*].message", hasItem(containsString("ON_HOLD → CLOSED"))));
	}

	@Test
	void legacyHistoryIsPreserved() throws Exception {
		Session mgr = manager();
		getAs(mgr, "/api/incidents/{id}", seededIncident(mgr, "EV-1022")).andExpect(jsonPath("$.data.status").value("ON_HOLD"));
		// 과거 종료 사건은 보고서 확정으로 닫힌다(원인 통계 시연용) — EV-1018은 원인 Man
		String closedWithReport = seededIncident(mgr, "EV-1018");
		getAs(mgr, "/api/incidents/{id}", closedWithReport).andExpect(jsonPath("$.data.status").value("CLOSED"));
		getAs(mgr, "/api/incidents/{id}/reports", closedWithReport)
			.andExpect(jsonPath("$.data.items", hasSize(1)))
			.andExpect(jsonPath("$.data.items[0].status").value("CONFIRMED"));
	}
}
