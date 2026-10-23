package com.fabcontext.api.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.Year;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import com.fabcontext.api.common.Json;
import com.fabcontext.api.support.ApiTestSupport;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/** 실 Postgres의 집계·권한·페이지·JSONB를 검증. 보고서 작성 모듈을 바꾸지 않고 전용 fixture만 사용한다. */
class CauseStatisticsApiTest extends ApiTestSupport {
    @Autowired JdbcTemplate jdbc;
    private final String prefix = "stat_" + UUID.randomUUID().toString().replace("-", "");
    private static final AtomicInteger DISPLAY = new AtomicInteger(900000);
    private int seq;

    private String incident(String process, String equipment, String detectedAt) {
        String id = prefix + "i" + (++seq);
        int display = DISPLAY.incrementAndGet();
        jdbc.update("""
            insert into incident (incident_id, display_id, display_seq, title, process_code, equipment_id,
                                  status, detected_at, created_at, updated_at, version, row_version)
            values (?, ?, ?, '통계 fixture', ?, ?, 'OPEN', ?, ?, ?, 1, 0)
            """, id, "EV-" + display, display, process, equipment, at(detectedAt), at(detectedAt), at(detectedAt));
        return id;
    }

    private String report(String incident, int version, String category, boolean confirmed) {
        String id = prefix + "r" + (++seq);
        String process = jdbc.queryForObject("select process_code from incident where incident_id=?", String.class, incident);
        jdbc.update("""
            insert into report (report_id, incident_id, process_code, version, status, title,
                                content_revision, source_revision, row_version, created_at, confirmed_at,
                                cause_category, cause_equipment_parameters, classified_by, classified_at, sections, evidence_ids)
            values (?, ?, ?, ?, ?, '통계 보고서', 1, 1, 0, ?, ?, ?, cast(? as jsonb), ?, ?, cast('[]' as jsonb), cast('[]' as jsonb))
            """, id, incident, process, version, confirmed ? "CONFIRMED" : "DRAFT",
                at("2092-02-01T00:00:00Z"), confirmed ? at("2092-03-01T00:00:00Z") : null, category,
                "MACHINE".equals(category) ? "[\"head_pressure\",\"slurry_flow\"]" : "[]",
                confirmed ? "USR-MGR-01" : null, confirmed ? at("2092-03-01T00:00:00Z") : null);
        return id;
    }

    private void fixture() {
        String january = incident("CMP", "CMP-PM3", "2090-12-31T15:00:00Z"); // 2091-01-01 KST
        report(january, 1, "MATERIAL", true);
        report(january, 2, "MACHINE", true); // 구버전 확정 중복도 사건당 1건
        report(january, 3, null, false); // 더 최신 초안이 확정본을 가리면 안 됨
        report(incident("CMP", "CMP-PM3", "2091-01-31T15:00:00Z"), 1, "HOLD", true); // 2월
        report(incident("ETCH", "ETCH-PM2", "2091-09-30T15:00:00Z"), 1, "METHOD", true); // 10월
        report(incident("CMP", "CMP-PM3", "2091-10-02T00:00:00Z"), 1, null, true); // 과거 미분류
        report(incident("CMP", "CMP-PM3", "2091-10-03T00:00:00Z"), 1, null, false); // 초안 제외
        incident("CMP", "CMP-PM3", "2091-10-04T00:00:00Z"); // 미작성 제외
        report(incident("CMP", "CMP-PM3", "2090-12-31T14:59:59Z"), 1, "MAN", true); // 연도 경계 밖
        report(incident("CMP", "CMP-PM3", "2091-12-31T15:00:00Z"), 1, "MAN", true); // 2092년
    }

    @AfterEach
    void cleanup() {
        jdbc.update("delete from report where report_id like ?", prefix + "%");
        jdbc.update("delete from incident where incident_id like ?", prefix + "%");
        jdbc.update("delete from user_account where user_id = ?", prefix + "user");
    }

    @Test
    void annualCountsUseOccurrenceKoreanBoundariesAndConfirmedIncidentDeduplication() throws Exception {
        fixture();
        getAs(manager(), "/api/cause-statistics?year=2091")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.dateBasis").value("DETECTED_AT"))
                .andExpect(jsonPath("$.data.timezone").value("Asia/Seoul"))
                .andExpect(jsonPath("$.data.annualTotalCount").value(4))
                .andExpect(jsonPath("$.data.totalCount").value(4))
                .andExpect(jsonPath("$.data.classifiedCount").value(3))
                .andExpect(jsonPath("$.data.unclassifiedCount").value(1))
                .andExpect(jsonPath("$.data.monthly.length()").value(12))
                .andExpect(jsonPath("$.data.monthly[0].count").value(1))
                .andExpect(jsonPath("$.data.monthly[1].count").value(1))
                .andExpect(jsonPath("$.data.monthly[9].count").value(2))
                .andExpect(jsonPath("$.data.monthly[11].count").value(0))
                .andExpect(jsonPath("$.data.categoryCounts.length()").value(5))
                .andExpect(jsonPath("$.data.categoryCounts[?(@.category=='MACHINE')].count").value(org.hamcrest.Matchers.contains(1)))
                .andExpect(jsonPath("$.data.categoryCounts[?(@.category=='MATERIAL')].count").value(org.hamcrest.Matchers.contains(0)));
    }

    @Test
    void selectedMonthsChangePieNotAnnualBarsAndListCombinesFilters() throws Exception {
        fixture();
        Session manager = manager();
        getAs(manager, "/api/cause-statistics?year=2091&months=2&months=10&months=10")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.months.length()").value(2))
                .andExpect(jsonPath("$.data.annualTotalCount").value(4))
                .andExpect(jsonPath("$.data.monthly[0].count").value(1))
                .andExpect(jsonPath("$.data.totalCount").value(3))
                .andExpect(jsonPath("$.data.unclassifiedCount").value(1))
                .andExpect(jsonPath("$.data.categoryCounts[?(@.category=='MACHINE')].count").value(org.hamcrest.Matchers.contains(0)));
        getAs(manager, "/api/cause-statistics/reports?year=2091&months=1&months=10&categories=MACHINE&categories=METHOD")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.totalElements").value(2))
                .andExpect(jsonPath("$.data.items[0].processCode").value("ETCH"))
                .andExpect(jsonPath("$.data.items[1].version").value(2))
                .andExpect(jsonPath("$.data.items[1].classification.equipmentParameters[0]").value("head_pressure"))
                .andExpect(jsonPath("$.data.items[1].createdAt").value("2092-02-01T00:00:00Z"));
        getAs(manager, "/api/cause-statistics/reports?year=2091&months=10&categories=MACHINE")
                .andExpect(jsonPath("$.data.items").isEmpty());
        // 분류 필터는 사건별 최신 확정본 선택 뒤 적용: 이전 Material 확정본이 다시 나타나면 안 됨
        getAs(manager, "/api/cause-statistics/reports?year=2091&categories=MATERIAL")
                .andExpect(jsonPath("$.data.totalElements").value(0));
    }

    @Test
    void listIsNewestOccurrenceFirstPagedAndLinksToExistingReportDetail() throws Exception {
        fixture();
        Session manager = manager();
        var first = getAs(manager, "/api/cause-statistics/reports?year=2091&size=1")
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.totalElements").value(4))
                .andExpect(jsonPath("$.data.totalPages").value(4))
                .andExpect(jsonPath("$.data.items[0].detectedAt").value("2091-10-02T00:00:00Z"));
        String report = json(first, "$.data.items[0].reportId");
        getAs(manager, "/api/reports/{id}", report).andExpect(status().isOk());
        var second = getAs(manager, "/api/cause-statistics/reports?year=2091&size=1&page=1")
                .andExpect(jsonPath("$.data.items[0].detectedAt").value("2091-09-30T15:00:00Z"));
        assertThat(json(second, "$.data.items[0].reportId").toString()).isNotEqualTo(report);
        getAs(manager, "/api/cause-statistics/reports?year=2091&size=1&page=4")
                .andExpect(jsonPath("$.data.items").isEmpty()).andExpect(jsonPath("$.data.totalElements").value(4));
    }

    @Test
    void equipmentAndProcessFiltersApplyToBothQueries() throws Exception {
        fixture();
        Session manager = manager();
        String filters = "year=2091&processCodes=CMP&processCodes=ETCH&equipmentIds=CMP-PM3&equipmentIds=ETCH-PM2";
        getAs(manager, "/api/cause-statistics?" + filters).andExpect(jsonPath("$.data.totalCount").value(4));
        getAs(manager, "/api/cause-statistics?year=2091&equipmentIds=ETCH-PM2")
                .andExpect(jsonPath("$.data.totalCount").value(1));
        getAs(manager, "/api/cause-statistics/reports?year=2091&processCodes=CMP&equipmentIds=ETCH-PM2")
                .andExpect(jsonPath("$.data.totalElements").value(0));
    }

    @Test
    void requiresManagerAndNeverLeaksOtherProcessThroughDefaultOrExplicitEquipmentFilters() throws Exception {
        fixture();
        mvc.perform(get("/api/cause-statistics")).andExpect(status().isUnauthorized());
        getAs(cmp(), "/api/cause-statistics").andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("MANAGER_ROLE_REQUIRED"));
        getAs(cmp(), "/api/cause-statistics/reports").andExpect(status().isForbidden());
        String name = prefix + "manager";
        jdbc.update("""
            insert into user_account (user_id, username, password_hash, display_name, role, process_scopes, team)
            select ?, ?, password_hash, '범위 관리자', 'MANAGER', cast('["CMP"]' as jsonb), team
            from user_account where user_id='USR-MGR-01'
            """, prefix + "user", name);
        Session limited = login(name);
        getAs(limited, "/api/cause-statistics?year=2091")
                .andExpect(jsonPath("$.data.totalCount").value(3))
                .andExpect(jsonPath("$.data.processCodes.length()").value(1));
        getAs(limited, "/api/cause-statistics/reports?year=2091")
                .andExpect(jsonPath("$.data.totalElements").value(3));
        getAs(limited, "/api/cause-statistics?year=2091&processCodes=ETCH")
                .andExpect(status().isForbidden());
        getAs(limited, "/api/cause-statistics/reports?year=2091&equipmentIds=ETCH-PM2")
                .andExpect(status().isForbidden());
    }

    @Test
    void emptyYearAndDefaultYearHaveStableZeroFilledShape() throws Exception {
        Session manager = manager();
        int year = Year.now(ZoneId.of("Asia/Seoul")).getValue();
        getAs(manager, "/api/cause-statistics").andExpect(jsonPath("$.data.year").value(year));
        getAs(manager, "/api/cause-statistics?year=2097")
                .andExpect(jsonPath("$.data.totalCount").value(0))
                .andExpect(jsonPath("$.data.classifiedCount").value(0))
                .andExpect(jsonPath("$.data.monthly.length()").value(12))
                .andExpect(jsonPath("$.data.categoryCounts.length()").value(5));
        getAs(manager, "/api/cause-statistics/reports?year=2097")
                .andExpect(jsonPath("$.data.items").isEmpty()).andExpect(jsonPath("$.data.totalPages").value(0));
    }

    @Test
    void invalidFiltersAndPaginationReturnClientErrors() throws Exception {
        Session manager = manager();
        for (String condition : List.of("year=0", "year=10000", "months=0", "months=13", "months=abc",
                "months=1&months=", "processCodes=PHOTO", "equipmentIds=unknown")) {
            getAs(manager, "/api/cause-statistics?" + condition).andExpect(status().isBadRequest());
        }
        for (String condition : List.of("categories=UNKNOWN", "page=-1", "size=0", "size=101")) {
            getAs(manager, "/api/cause-statistics/reports?" + condition).andExpect(status().isBadRequest());
        }
        getAs(manager, "/api/cause-statistics/reports?page=2147483647&size=100")
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.items").isEmpty());
    }

    @Test
    void swaggerExposesConcreteResponsesAndMultiSelectFilters() throws Exception {
        String document = mvc.perform(get("/v3/api-docs")).andExpect(status().isOk()).andReturn().getResponse()
                .getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
        var root = Json.tree(Json.read(document, new tools.jackson.core.type.TypeReference<java.util.Map<String, Object>>() {}));
        assertThat(root.at("/paths/~1api~1cause-statistics/get/operationId").asText()).isEqualTo("getCauseStatistics");
        assertThat(root.at("/paths/~1api~1cause-statistics/get/responses/200/content/application~1json/schema/$ref").asText())
                .endsWith("/ApiResponseCauseStatistics");
        assertThat(root.at("/paths/~1api~1cause-statistics~1reports/get/responses/200/content/application~1json/schema/$ref").asText())
                .endsWith("/ApiResponseCauseStatisticsReportPage");
        assertThat(root.at("/components/schemas/CauseStatistics/properties/monthly").isMissingNode()).isFalse();
        assertThat(root.at("/components/schemas/CauseStatisticsReportPage/properties/items").isMissingNode()).isFalse();
        assertThat(root.at("/paths/~1api~1cause-statistics~1reports/get/parameters").toString()).contains("categories", "months");
        String export = System.getenv("FAB_OPENAPI_EXPORT");
        if (export != null) java.nio.file.Files.writeString(java.nio.file.Path.of(export), document);
    }

    private static Timestamp at(String instant) { return Timestamp.from(Instant.parse(instant)); }
}
