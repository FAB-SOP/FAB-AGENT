package com.fabcontext.api.domain.report;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Map;
import java.util.Set;

import com.fabcontext.api.common.ApiException;
import org.springframework.http.HttpStatus;

/**
 * 보고서 목록 조회 조건 — 만들 때 검사 · 정규화를 끝내 둔다. 비어 있는 조건은 null이고 쿼리에 넣지 않는다.
 * 기간은 화면과 같은 한국 시간 날짜이며, 종료일 당일을 포함한다.
 */
public final class ReportSearchCondition {

	public static final int DEFAULT_SIZE = 20;
	public static final int MAX_SIZE = 100;
	public static final int MAX_KEYWORD_LENGTH = 100;
	/** 분류가 없는 보고서(초안 · 분류 기능 도입 전 확정본) */
	public static final String UNCLASSIFIED = "UNCLASSIFIED";

	private static final Set<String> PROCESSES = Set.of("CVD", "ETCH", "CMP");
	private static final ZoneId KOREA = ZoneId.of("Asia/Seoul");

	private final String processCode;
	private final String titleKeyword;
	private final ReportCauseCategory category;
	private final boolean unclassified;
	private final String equipmentParameter;
	private final Instant detectedFrom;
	private final Instant detectedToExclusive;
	private final int page;
	private final int size;

	private ReportSearchCondition(String processCode, String titleKeyword, ReportCauseCategory category,
			boolean unclassified, String equipmentParameter, Instant detectedFrom, Instant detectedToExclusive,
			int page, int size) {
		this.processCode = processCode;
		this.titleKeyword = titleKeyword;
		this.category = category;
		this.unclassified = unclassified;
		this.equipmentParameter = equipmentParameter;
		this.detectedFrom = detectedFrom;
		this.detectedToExclusive = detectedToExclusive;
		this.page = page;
		this.size = size;
	}

	public static ReportSearchCondition of(String processCode, String keyword, String category,
			String equipmentParameter, LocalDate detectedFrom, LocalDate detectedTo, int page, int size) {
		if (processCode == null || !PROCESSES.contains(processCode)) {
			throw ApiException.unprocessable("INVALID_PROCESS_CODE", "processCode는 CVD · ETCH · CMP 중 하나여야 합니다.");
		}
		if (page < 0 || size < 1 || size > MAX_SIZE) {
			throw ApiException.badRequest("INVALID_PAGE_SIZE",
				"page는 0 이상, size는 1~" + MAX_SIZE + " 사이여야 합니다.");
		}
		if (detectedFrom != null && detectedTo != null && detectedFrom.isAfter(detectedTo)) {
			throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "INVALID_DETECTED_RANGE",
				"이상 발생 기간의 시작일이 종료일보다 늦습니다.", Map.of("detectedFrom", detectedFrom.toString(),
					"detectedTo", detectedTo.toString()));
		}
		boolean unclassified = UNCLASSIFIED.equals(category);
		ReportCauseCategory causeCategory = unclassified ? null : parseCategory(category);
		String parameter = causeCategory == ReportCauseCategory.MACHINE ? blankToNull(equipmentParameter) : null;
		return new ReportSearchCondition(processCode, normalizeKeyword(keyword), causeCategory, unclassified, parameter,
			detectedFrom == null ? null : detectedFrom.atStartOfDay(KOREA).toInstant(),
			detectedTo == null ? null : detectedTo.plusDays(1).atStartOfDay(KOREA).toInstant(), page, size);
	}

	/** 제목 부분 일치 패턴 — LIKE 기호(\ % _)는 글자 그대로 찾도록 이스케이프한다(ESCAPE '\') */
	public String titleLikePattern() {
		if (titleKeyword == null) {
			return null;
		}
		String escaped = titleKeyword.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
		return "%" + escaped + "%";
	}

	public int offset() {
		return page * size;
	}

	private static String normalizeKeyword(String keyword) {
		String trimmed = blankToNull(keyword);
		if (trimmed != null && trimmed.length() > MAX_KEYWORD_LENGTH) {
			throw ApiException.unprocessable("INVALID_KEYWORD", "검색어는 " + MAX_KEYWORD_LENGTH + "자 이하여야 합니다.");
		}
		return trimmed;
	}

	private static ReportCauseCategory parseCategory(String category) {
		String value = blankToNull(category);
		if (value == null) {
			return null;
		}
		try {
			return ReportCauseCategory.valueOf(value);
		}
		catch (IllegalArgumentException unknownCategory) {
			throw ApiException.unprocessable("INVALID_CAUSE_CATEGORY",
				"category는 MATERIAL · MAN · METHOD · MACHINE · HOLD · UNCLASSIFIED 중 하나여야 합니다.");
		}
	}

	private static String blankToNull(String value) {
		return value == null || value.isBlank() ? null : value.trim();
	}

	public String processCode() {
		return processCode;
	}

	public String titleKeyword() {
		return titleKeyword;
	}

	public ReportCauseCategory category() {
		return category;
	}

	public boolean unclassified() {
		return unclassified;
	}

	public String equipmentParameter() {
		return equipmentParameter;
	}

	public Instant detectedFrom() {
		return detectedFrom;
	}

	public Instant detectedToExclusive() {
		return detectedToExclusive;
	}

	public int page() {
		return page;
	}

	public int size() {
		return size;
	}
}
