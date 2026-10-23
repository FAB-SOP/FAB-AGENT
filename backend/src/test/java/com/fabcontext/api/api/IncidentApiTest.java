package com.fabcontext.api.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

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
	void managerTransitionsFollowStateRules() throws Exception {
		Session etch = cmp();
		Session mgr = manager();
		String id = newIncident(etch, false).incidentId();
		long v = incidentVersion(etch, id);
		String body = "{\"targetStatus\":\"%s\",\"reason\":\"%s\",\"expectedVersion\":%d}";

		postAs(etch, "/api/incidents/{id}/transitions", body.formatted("ON_HOLD", "r", v), id)
			.andExpect(status().isForbidden()).andExpect(jsonPath("$.error.code").value("MANAGER_ROLE_REQUIRED"));
		postAs(mgr, "/api/incidents/{id}/transitions", body.formatted("INVESTIGATING", "r", v), id)
			.andExpect(status().isConflict()).andExpect(jsonPath("$.error.code").value("INVALID_TRANSITION"));
		postAs(mgr, "/api/incidents/{id}/transitions", body.formatted("ON_HOLD", "r", v - 1), id)
			.andExpect(status().isConflict()).andExpect(jsonPath("$.error.details.currentVersion").value((int) v));
		postAs(mgr, "/api/incidents/{id}/transitions", body.formatted("OPEN", "r", v), id)
			.andExpect(status().isConflict());
		postAs(mgr, "/api/incidents/{id}/transitions", "{\"targetStatus\":\"ON_HOLD\",\"expectedVersion\":" + v + "}", id)
			.andExpect(status().isUnprocessableEntity());

		// 사건 종료는 보고서 확정으로만(003) — 관리자의 새 보류 · 수동 종료는 없다
		postAs(mgr, "/api/incidents/{id}/transitions", body.formatted("ON_HOLD", "데이터 대기", v), id)
			.andExpect(status().isConflict()).andExpect(jsonPath("$.error.code").value("TRANSITION_REMOVED"));
		postAs(mgr, "/api/incidents/{id}/transitions", body.formatted("CLOSED", "종료", v), id)
			.andExpect(status().isConflict()).andExpect(jsonPath("$.error.code").value("TRANSITION_REMOVED"));
		getAs(etch, "/api/incidents/{id}", id).andExpect(jsonPath("$.data.status").value(not(is("CLOSED"))));

		// 기존에 보류된 사건(도입 전 이력)의 해제만 남는다
		jdbc.update("update incident set status = 'ON_HOLD' where incident_id = ?", id);
		v = incidentVersion(etch, id);
		postAs(etch, "/api/incidents/{id}/transitions", body.formatted("INVESTIGATING", "재개", v), id)
			.andExpect(status().isForbidden());
		postAs(mgr, "/api/incidents/{id}/transitions", body.formatted("INVESTIGATING", "재개", v), id)
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.status").value("INVESTIGATING"))
			.andExpect(jsonPath("$.data.timeline[-1].message", containsString("ON_HOLD → INVESTIGATING")));
	}

	@Test
	void legacyHistoryIsPreserved() throws Exception {
		Session mgr = manager();
		getAs(mgr, "/api/incidents/{id}", seededIncident(mgr, "EV-1022")).andExpect(jsonPath("$.data.status").value("ON_HOLD"));
		String closedWithoutReport = seededIncident(mgr, "EV-1018");
		getAs(mgr, "/api/incidents/{id}", closedWithoutReport).andExpect(jsonPath("$.data.status").value("CLOSED"));
		getAs(mgr, "/api/incidents/{id}/reports", closedWithoutReport).andExpect(jsonPath("$.data.items", hasSize(0)));
	}
}
