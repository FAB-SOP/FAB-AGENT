package com.fabcontext.api.domain.statistics;

import java.time.Instant;
import java.util.List;

import com.fabcontext.api.domain.report.ReportCauseCategory;
import com.fabcontext.api.domain.report.ReportDtos.ClassificationView;
import io.swagger.v3.oas.annotations.media.Schema;

public final class CauseStatisticsDtos {
    private CauseStatisticsDtos() {}

    public record CategoryCount(ReportCauseCategory category, long count) {}
    public record MonthCount(int month, long count) {}

    @Schema(name = "CauseStatistics", description = "확정 보고서가 있는 사건만 집계. 발생일 기준이며 월 막대는 연간, 파이는 선택 월 기준")
    public record Statistics(int year, String dateBasis, String timezone, List<Integer> months,
            List<String> processCodes, List<String> equipmentIds, List<Integer> availableYears,
            long annualTotalCount, List<MonthCount> monthly, long totalCount, long classifiedCount,
            long unclassifiedCount, List<CategoryCount> categoryCounts) {}

    @Schema(name = "CauseStatisticsReport", description = "사건별 최종 확정 보고서. 분류가 없는 과거 확정본은 classification=null")
    public record ReportItem(String reportId, String incidentId, String incidentDisplayId, String title,
            String processCode, String equipmentId, int version, Instant detectedAt, Instant createdAt,
            Instant confirmedAt, ClassificationView classification) {}

    @Schema(name = "CauseStatisticsReportPage")
    public record ReportPage(int year, String dateBasis, String timezone, List<Integer> months,
            List<String> processCodes, List<String> equipmentIds, List<ReportCauseCategory> categories,
            List<ReportItem> items, int page, int size, long totalElements, long totalPages) {}
}
