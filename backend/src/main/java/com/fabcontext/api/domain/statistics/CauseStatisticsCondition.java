package com.fabcontext.api.domain.statistics;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Set;

import com.fabcontext.api.common.ApiException;
import com.fabcontext.api.domain.report.ReportCauseCategory;

/** 발생일(한국 시간) 기준. 같은 필터의 복수 값은 OR, 서로 다른 필터는 AND다. */
record CauseStatisticsCondition(int year, List<Integer> months, List<String> processCodes,
        List<String> equipmentIds, List<ReportCauseCategory> categories, int page, int size) {

    static final ZoneId KOREA = ZoneId.of("Asia/Seoul");
    static final List<String> PROCESSES = List.of("CVD", "ETCH", "CMP");

    static CauseStatisticsCondition of(int year, List<Integer> months, List<String> processes,
            List<String> equipment, List<String> categories, int page, int size) {
        if (year < 1 || year > 9998) {
            throw ApiException.badRequest("INVALID_YEAR", "year는 1~9998이어야 합니다.");
        }
        if (months != null && months.stream().anyMatch(m -> m == null)) {
            throw ApiException.badRequest("INVALID_MONTH", "months에 빈 값을 넣을 수 없습니다.");
        }
        List<Integer> selectedMonths = months == null ? List.of() : months.stream().distinct().sorted().toList();
        if (selectedMonths.stream().anyMatch(m -> m < 1 || m > 12)) {
            throw ApiException.badRequest("INVALID_MONTH", "months는 1~12여야 합니다.");
        }
        List<String> selectedProcesses = values(processes, 3);
        if (!Set.copyOf(PROCESSES).containsAll(selectedProcesses)) {
            throw ApiException.badRequest("INVALID_PROCESS_CODE", "processCodes는 CVD · ETCH · CMP입니다.");
        }
        List<ReportCauseCategory> selectedCategories;
        try {
            selectedCategories = values(categories, 5).stream().map(ReportCauseCategory::valueOf).toList();
        } catch (IllegalArgumentException e) {
            throw ApiException.badRequest("INVALID_CAUSE_CATEGORY", "categories는 MATERIAL · MAN · METHOD · MACHINE · HOLD입니다.");
        }
        if (page < 0 || size < 1 || size > 100) {
            throw ApiException.badRequest("INVALID_PAGE_SIZE", "page는 0 이상, size는 1~100이어야 합니다.");
        }
        return new CauseStatisticsCondition(year, selectedMonths, selectedProcesses, values(equipment, 100),
                selectedCategories, page, size);
    }

    private static List<String> values(List<String> values, int max) {
        if (values == null) return List.of();
        if (values.size() > max || values.stream().anyMatch(v -> v == null || v.isBlank() || v.length() > 100)) {
            throw ApiException.badRequest("INVALID_FILTER", "필터 값이 비었거나 허용 개수·길이를 초과했습니다.");
        }
        return values.stream().distinct().sorted().toList();
    }

    Instant from() { return LocalDate.of(year, 1, 1).atStartOfDay(KOREA).toInstant(); }
    Instant toExclusive() { return LocalDate.of(year + 1, 1, 1).atStartOfDay(KOREA).toInstant(); }
    long offset() { return (long) page * size; }
}
