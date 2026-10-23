package com.fabcontext.api.domain.statistics;

import java.util.List;

import com.fabcontext.api.auth.AuthUser;
import com.fabcontext.api.common.ApiResponse;
import com.fabcontext.api.domain.statistics.CauseStatisticsDtos.ReportPage;
import com.fabcontext.api.domain.statistics.CauseStatisticsDtos.Statistics;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping(value = "/api/cause-statistics", produces = MediaType.APPLICATION_JSON_VALUE)
@Tag(name = "cause-statistics", description = "관리자·설비정비팀용 사건 원인 통계 · 확정 보고서만 · 사건 발생일 기준")
@RequiredArgsConstructor
public class CauseStatisticsController {
    private final CauseStatisticsService service;

    @Operation(operationId = "getCauseStatistics", summary = "연간 월별 건수와 선택 월 원인 분포",
            description = """
            관리자 또는 설비정비팀 권한 및 공정 범위 필요. year 생략 시 한국 시간 올해. 확정 보고서가 있는 사건만 1건씩 집계합니다.
            monthly는 월 선택과 무관하게 해당 연도 12개월을 유지하고, categoryCounts/totalCount는 선택 월 기준입니다.
            원인 선택은 파이 강조와 보고서 목록 필터에만 적용하므로 이 API에는 categories를 전달하지 않습니다.
            4M+HOLD는 0건도 반환합니다. 과거 미분류 확정본은 unclassifiedCount에 별도로 표시하고 HOLD로 치환하지 않습니다.
            파이 비율의 분모는 classifiedCount입니다. availableYears는 권한·공정·설비 범위의 발생 연도와 올해/선택 연도입니다.
            400 INVALID_YEAR/INVALID_MONTH/INVALID_PROCESS_CODE/INVALID_FILTER/INVALID_EQUIPMENT_ID,
            401 UNAUTHENTICATED, 403 CAUSE_STATISTICS_ACCESS_DENIED/PROCESS_SCOPE_DENIED.
            """)
    @GetMapping
    public ApiResponse<Statistics> statistics(@AuthenticationPrincipal AuthUser user,
            @Parameter(description = "사건 발생 연도. 생략 시 한국 시간 올해", example = "2026") @RequestParam(required = false) Integer year,
            @Parameter(description = "발생 월 복수 선택: months=9&months=10. 생략 시 연간") @RequestParam(required = false) List<Integer> months,
            @Parameter(description = "공정 복수 선택. 생략 시 로그인 사용자의 허용 공정 전체") @RequestParam(required = false) List<String> processCodes,
            @Parameter(description = "사건 발생 설비 복수 선택") @RequestParam(required = false) List<String> equipmentIds) {
        return ApiResponse.of(service.statistics(user, CauseStatisticsCondition.of(
                year == null ? service.currentYear() : year, months, processCodes, equipmentIds, null, 0, 20)));
    }

    @Operation(operationId = "getCauseStatisticsReports", summary = "선택 월·원인의 확정 보고서 목록",
            description = """
            관리자 또는 설비정비팀 권한 및 공정 범위 필요. 사건 발생일 내림차순, 같은 시각은 incidentId 오름차순으로 고정합니다.
            같은 필터 값은 OR, 월·분류·공정·설비는 AND입니다. 월/분류 생략은 전체입니다.
            초안·미작성 사건은 제외하며 사건당 최신 확정본 하나를 반환합니다. 더 최신인 초안은 확정본을 가리지 않습니다.
            reportId로 기존 GET /api/reports/{reportId} 상세 API를 이용합니다.
            detectedAt=발생일, createdAt=해당 보고서 판의 작성일, confirmedAt=확정일. 모두 UTC ISO-8601입니다.
            과거 미분류 확정본은 분류 필터가 없을 때만 포함하며 classification=null입니다.
            400 INVALID_YEAR/INVALID_MONTH/INVALID_PROCESS_CODE/INVALID_CAUSE_CATEGORY/INVALID_FILTER/INVALID_EQUIPMENT_ID/INVALID_PAGE_SIZE,
            401 UNAUTHENTICATED, 403 CAUSE_STATISTICS_ACCESS_DENIED/PROCESS_SCOPE_DENIED.
            """)
    @GetMapping("/reports")
    public ApiResponse<ReportPage> reports(@AuthenticationPrincipal AuthUser user,
            @Parameter(description = "사건 발생 연도. 생략 시 한국 시간 올해", example = "2026") @RequestParam(required = false) Integer year,
            @Parameter(description = "발생 월 복수 선택: months=9&months=10") @RequestParam(required = false) List<Integer> months,
            @Parameter(description = "공정 복수 선택") @RequestParam(required = false) List<String> processCodes,
            @Parameter(description = "사건 발생 설비 복수 선택") @RequestParam(required = false) List<String> equipmentIds,
            @Parameter(description = "파이 선택 분류 복수 값: MACHINE/MATERIAL/METHOD/MAN/HOLD") @RequestParam(required = false) List<String> categories,
            @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size) {
        return ApiResponse.of(service.reports(user, CauseStatisticsCondition.of(
                year == null ? service.currentYear() : year, months, processCodes, equipmentIds, categories, page, size)));
    }
}
