package com.fabcontext.api.domain.report;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.fabcontext.api.domain.report.ReportDtos.ClassificationView;
import com.fabcontext.api.domain.report.ReportSearchDtos.ReportListItem;
import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

/**
 * 보고서 목록 조회 SQL — 보고서와 사건을 한 번에 조인해 읽는다(보고서마다 사건을 따로 읽는 N+1 없음).
 * 조건은 있는 것만 WHERE에 넣는다. 빈 조건을 (:x IS NULL OR …)로 섞으면 PostgreSQL 일반 계획에서 인덱스를 못 쓸 수 있다.
 * 값은 모두 바인딩 파라미터다. 정렬은 이상 감지일 최신순 → 보고서 ID로 고정해 페이지 사이 순서가 흔들리지 않게 한다.
 */
@Repository
@RequiredArgsConstructor
public class ReportSearchQuery {

	private static final String SELECT = """
		select r.report_id, r.incident_id, i.display_id, r.process_code, r.version, r.status, r.title,
		       r.content_revision, r.created_at, r.confirmed_at, i.detected_at,
		       r.cause_category,
		       array_to_string(array(select jsonb_array_elements_text(r.cause_equipment_parameters)), ','),
		       r.classified_by, r.classified_at
		from {h-schema}report r
		join {h-schema}incident i on i.incident_id = r.incident_id
		""";

	private static final String ORDER_AND_PAGE = """
		order by i.detected_at desc, r.report_id
		limit :limit offset :offset""";

	private final EntityManager entityManager;

	/** 한 페이지보다 한 건 더 읽는다 — 그 한 건이 있으면 다음 페이지가 있다 */
	public List<ReportListItem> findPagePlusOne(ReportSearchCondition condition) {
		Map<String, Object> parameters = new LinkedHashMap<>();
		String sql = SELECT + whereClause(condition, parameters) + ORDER_AND_PAGE;
		Query query = entityManager.createNativeQuery(sql);
		parameters.forEach(query::setParameter);
		query.setParameter("limit", condition.size() + 1);
		query.setParameter("offset", condition.offset());
		List<?> rows = query.getResultList();
		return rows.stream().map(row -> toItem((Object[]) row)).toList();
	}

	private static String whereClause(ReportSearchCondition condition, Map<String, Object> parameters) {
		List<String> predicates = new ArrayList<>();
		// 사건 쪽 공정도 같이 건다 — 보고서와 사건의 공정은 늘 같지만, 이 조건이 있어야 incident(process_code, detected_at)
		// 인덱스를 정렬 순서대로 읽고 첫 페이지에서 멈출 수 있다(측정: 첫 페이지 17ms → 0.2ms, 보고서 10만 건)
		predicates.add("r.process_code = :processCode");
		predicates.add("i.process_code = :processCode");
		parameters.put("processCode", condition.processCode());
		if (condition.titleLikePattern() != null) {
			predicates.add("r.title ilike :titlePattern escape '\\'");
			parameters.put("titlePattern", condition.titleLikePattern());
		}
		if (condition.unclassified()) {
			predicates.add("r.cause_category is null");
		}
		else if (condition.category() != null) {
			predicates.add("r.cause_category = :category");
			parameters.put("category", condition.category().name());
		}
		if (condition.equipmentParameter() != null) {
			predicates.add("r.cause_equipment_parameters @> jsonb_build_array(cast(:equipmentParameter as text))");
			parameters.put("equipmentParameter", condition.equipmentParameter());
		}
		if (condition.detectedFrom() != null) {
			predicates.add("i.detected_at >= :detectedFrom");
			parameters.put("detectedFrom", condition.detectedFrom());
		}
		if (condition.detectedToExclusive() != null) {
			predicates.add("i.detected_at < :detectedToExclusive");
			parameters.put("detectedToExclusive", condition.detectedToExclusive());
		}
		return "where " + String.join("\n  and ", predicates) + "\n";
	}

	private static ReportListItem toItem(Object[] row) {
		String category = (String) row[11];
		ClassificationView classification = category == null ? null
				: new ClassificationView(ReportCauseCategory.valueOf(category), splitParameters((String) row[12]),
					(String) row[13], toInstant(row[14]));
		return new ReportListItem((String) row[0], (String) row[1], (String) row[2], (String) row[3],
			((Number) row[4]).intValue(), ReportStatus.valueOf((String) row[5]), (String) row[6],
			((Number) row[7]).longValue(), toInstant(row[8]), toInstant(row[9]), toInstant(row[10]), classification);
	}

	private static List<String> splitParameters(String joined) {
		return joined == null || joined.isEmpty() ? List.of() : Arrays.asList(joined.split(","));
	}

	/** 드라이버 · Hibernate 버전에 따라 timestamptz가 Timestamp · OffsetDateTime · Instant로 온다 */
	private static Instant toInstant(Object value) {
		return switch (value) {
			case null -> null;
			case Instant instant -> instant;
			case OffsetDateTime offsetDateTime -> offsetDateTime.toInstant();
			case Timestamp timestamp -> timestamp.toInstant();
			default -> throw new IllegalStateException("시각 열 형식을 알 수 없습니다: " + value.getClass());
		};
	}
}
