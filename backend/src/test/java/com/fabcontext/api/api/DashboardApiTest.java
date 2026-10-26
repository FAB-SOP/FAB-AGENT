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

@DisplayName("P02 대시보드")
class DashboardApiTest extends ApiTestSupport {

	@Test
	void countsAndBreakdownsWithinScope() throws Exception {
		getAs(cmp(), "/api/dashboard")
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.period").value("7d"))
			.andExpect(jsonPath("$.data.bucket").value("DAY"))
			// 기간 집계는 발견 시각 기준이다. 데이터 팩 사건(EV-1006 등)은 발견 시각이 팩 날짜로 고정돼 날이 지나면 7일 밖으로 빠진다.
			// 시드 과거 사건 EV-1020 · 1021 · 1022(CMP)는 실행 시각 기준 5 · 3 · 2일 전이라 언제 돌려도 7일 안에 있다
			.andExpect(jsonPath("$.data.counts.total").value(greaterThanOrEqualTo(3)))
			.andExpect(jsonPath("$.data.counts.active").value(greaterThanOrEqualTo(2)))
			.andExpect(jsonPath("$.data.byStatus.CLOSED").value(greaterThanOrEqualTo(3)))
			.andExpect(jsonPath("$.data.byProcess[*].processCode", contains("CMP")))
			.andExpect(jsonPath("$.data.byEquipment[*].processCode", everyItem(is("CMP"))))
			.andExpect(jsonPath("$.data.refreshNote").value(containsString("다시 조회")));
		getAs(cvd(), "/api/dashboard").andExpect(jsonPath("$.data.counts.pendingApprovals").value(greaterThanOrEqualTo(1)));
		getAs(manager(), "/api/dashboard").andExpect(jsonPath("$.data.byProcess", hasSize(3)));
	}

	@Test
	void trendBucketsMatchPeriodAndSumToTotal() throws Exception {
		Session etch = cmp();
		var day = getAs(etch, "/api/dashboard?period=24h").andExpect(jsonPath("$.data.bucket").value("HOUR"));
		List<Integer> hourly = json(day, "$.data.trend[*].count");
		assertThat(hourly.size()).isBetween(24, 25);
		assertThat(hourly.stream().mapToInt(Integer::intValue).sum()).isEqualTo(((Number) json(day, "$.data.counts.total")).intValue());
		var month = getAs(etch, "/api/dashboard?period=30d");
		List<Integer> daily = json(month, "$.data.trend[*].count");
		assertThat(daily.size()).isBetween(30, 32);
		assertThat(daily.stream().mapToInt(Integer::intValue).sum()).isEqualTo(((Number) json(month, "$.data.counts.total")).intValue());
	}

	@Test
	void validation() throws Exception {
		Session etch = cmp();
		getAs(etch, "/api/dashboard?period=1y").andExpect(status().isBadRequest()).andExpect(jsonPath("$.error.code").value("INVALID_PERIOD"));
		getAs(etch, "/api/dashboard?processCode=CVD").andExpect(status().isForbidden());
		getAs(etch, "/api/dashboard?processCode=CMP").andExpect(jsonPath("$.data.processCode").value("CMP"));
	}
}
