package com.fabcontext.api.domain.statistics;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.fabcontext.api.common.Json;
import com.fabcontext.api.domain.report.ReportCauseCategory;
import com.fabcontext.api.domain.report.ReportDtos.ClassificationView;
import com.fabcontext.api.domain.statistics.CauseStatisticsDtos.ReportItem;
import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;
import tools.jackson.core.type.TypeReference;

/** 읽기 전용 집계. DB에서 사건별 확정본을 먼저 고른 뒤 분류 필터를 적용해 버전 중복을 막는다. */
@Repository
@RequiredArgsConstructor
class CauseStatisticsQuery {
    private final EntityManager entityManager;

    record Bucket(int month, String category, long count) {}
    record EquipmentScope(String equipmentId, String processCode) {}

    private String source(CauseStatisticsCondition c, List<String> scopes, boolean year,
            Map<String, Object> parameters) {
        StringBuilder where = new StringBuilder("r.status = 'CONFIRMED' and i.process_code in (:scopes) and r.process_code = i.process_code");
        parameters.put("scopes", scopes);
        if (!c.equipmentIds().isEmpty()) {
            where.append(" and i.equipment_id in (:equipment)");
            parameters.put("equipment", c.equipmentIds());
        }
        if (year) {
            where.append(" and i.detected_at >= :from and i.detected_at < :to");
            parameters.put("from", c.from());
            parameters.put("to", c.toExclusive());
        }
        return """
            with ranked as (
              select r.report_id, r.incident_id, r.process_code, r.version, r.title, r.created_at, r.confirmed_at,
                     r.cause_category, r.cause_equipment_parameters, r.classified_by, r.classified_at,
                     i.display_id as incident_display_id, i.equipment_id, i.detected_at,
                     cast(extract(month from i.detected_at at time zone 'Asia/Seoul') as integer) as detected_month,
                     row_number() over (partition by r.incident_id order by r.version desc, r.report_id desc) as report_rank
              from {h-schema}report r join {h-schema}incident i on i.incident_id = r.incident_id
              where %s
            ), selected as (select * from ranked where report_rank = 1)
            """.formatted(where);
    }

    List<Bucket> buckets(CauseStatisticsCondition c, List<String> scopes) {
        Map<String, Object> p = new LinkedHashMap<>();
        String sql = source(c, scopes, true, p) + """
            select detected_month, cause_category, count(*) from selected
            group by detected_month, cause_category order by detected_month
            """;
        return rows(sql, p).stream().map(row -> {
            Object[] r = (Object[]) row;
            return new Bucket(((Number) r[0]).intValue(), (String) r[1], ((Number) r[2]).longValue());
        }).toList();
    }

    List<Integer> years(CauseStatisticsCondition c, List<String> scopes) {
        Map<String, Object> p = new LinkedHashMap<>();
        String sql = source(c, scopes, false, p) + """
            select distinct cast(extract(year from detected_at at time zone 'Asia/Seoul') as integer)
            from selected where detected_at is not null order by 1 desc
            """;
        return rows(sql, p).stream().map(v -> ((Number) v).intValue()).toList();
    }

    List<ReportItem> reports(CauseStatisticsCondition c, List<String> scopes) {
        Map<String, Object> p = new LinkedHashMap<>();
        String sql = source(c, scopes, true, p) + """
            select report_id, incident_id, incident_display_id, title, process_code, equipment_id, version,
                   detected_at, created_at, confirmed_at, cause_category,
                   cast(cause_equipment_parameters as text), classified_by, classified_at
            from selected
            """ + filters(c, p) + " order by detected_at desc, incident_id asc limit :limit offset :offset";
        p.put("limit", c.size());
        p.put("offset", c.offset());
        return rows(sql, p).stream().map(v -> item((Object[]) v)).toList();
    }

    long count(CauseStatisticsCondition c, List<String> scopes) {
        Map<String, Object> p = new LinkedHashMap<>();
        String sql = source(c, scopes, true, p) + "select count(*) from selected " + filters(c, p);
        return ((Number) query(sql, p).getSingleResult()).longValue();
    }

    /** 설비를 명시하면 소속 공정 권한도 확인한다. 설비명 접두사로 권한을 추정하지 않는다. */
    List<EquipmentScope> equipmentScopes(List<String> equipmentIds) {
        return rows("""
            select equipment_id, process_code from {h-schema}route_run where equipment_id in (:equipment)
            union select equipment_id, process_code from {h-schema}incident where equipment_id in (:equipment)
            """, Map.of("equipment", equipmentIds)).stream().map(row -> {
                Object[] r = (Object[]) row;
                return new EquipmentScope((String) r[0], (String) r[1]);
            }).toList();
    }

    private static String filters(CauseStatisticsCondition c, Map<String, Object> p) {
        List<String> clauses = new ArrayList<>();
        if (!c.months().isEmpty()) {
            clauses.add("detected_month in (:months)");
            p.put("months", c.months());
        }
        if (!c.categories().isEmpty()) {
            clauses.add("cause_category in (:categories)");
            p.put("categories", c.categories().stream().map(Enum::name).toList());
        }
        return clauses.isEmpty() ? "" : "where " + String.join(" and ", clauses);
    }

    private List<?> rows(String sql, Map<String, Object> p) { return query(sql, p).getResultList(); }

    private Query query(String sql, Map<String, Object> p) {
        Query query = entityManager.createNativeQuery(sql);
        p.forEach(query::setParameter);
        return query;
    }

    private static ReportItem item(Object[] r) {
        ClassificationView classification = r[10] == null ? null : new ClassificationView(
                ReportCauseCategory.valueOf((String) r[10]),
                r[11] == null ? List.of() : Json.read((String) r[11], new TypeReference<List<String>>() {}),
                (String) r[12], instant(r[13]));
        return new ReportItem((String) r[0], (String) r[1], (String) r[2], (String) r[3], (String) r[4],
                (String) r[5], ((Number) r[6]).intValue(), instant(r[7]), instant(r[8]), instant(r[9]), classification);
    }

    private static Instant instant(Object value) {
        return switch (value) {
            case null -> null;
            case Instant i -> i;
            case OffsetDateTime o -> o.toInstant();
            case Timestamp t -> t.toInstant();
            default -> throw new IllegalStateException("지원하지 않는 시각 타입: " + value.getClass());
        };
    }
}
