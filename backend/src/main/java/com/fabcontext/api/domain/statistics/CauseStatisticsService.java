package com.fabcontext.api.domain.statistics;

import java.time.Clock;
import java.time.Year;
import java.util.Arrays;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import com.fabcontext.api.auth.AccessPolicy;
import com.fabcontext.api.auth.AuthUser;
import com.fabcontext.api.auth.Role;
import com.fabcontext.api.common.ApiException;
import com.fabcontext.api.domain.report.ReportCauseCategory;
import com.fabcontext.api.domain.statistics.CauseStatisticsDtos.CategoryCount;
import com.fabcontext.api.domain.statistics.CauseStatisticsDtos.MonthCount;
import com.fabcontext.api.domain.statistics.CauseStatisticsDtos.ReportPage;
import com.fabcontext.api.domain.statistics.CauseStatisticsDtos.Statistics;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class CauseStatisticsService {
    private final AccessPolicy access;
    private final CauseStatisticsQuery query;
    private final Clock clock;

    int currentYear() { return Year.now(clock.withZone(CauseStatisticsCondition.KOREA)).getValue(); }

    Statistics statistics(AuthUser user, CauseStatisticsCondition c) {
        List<String> scopes = scopes(user, c);
        var buckets = query.buckets(c, scopes);
        long[] monthly = new long[13];
        Map<ReportCauseCategory, Long> counts = new EnumMap<>(ReportCauseCategory.class);
        long total = 0, unclassified = 0;
        for (var bucket : buckets) {
            monthly[bucket.month()] += bucket.count();
            if (c.months().isEmpty() || c.months().contains(bucket.month())) {
                total += bucket.count();
                if (bucket.category() == null) unclassified += bucket.count();
                else counts.merge(ReportCauseCategory.valueOf(bucket.category()), bucket.count(), Long::sum);
            }
        }
        List<CategoryCount> categories = Arrays.stream(ReportCauseCategory.values())
                .map(category -> new CategoryCount(category, counts.getOrDefault(category, 0L))).toList();
        List<MonthCount> months = IntStream.rangeClosed(1, 12).mapToObj(m -> new MonthCount(m, monthly[m])).toList();
        List<Integer> years = Stream.concat(query.years(c, scopes).stream(), Stream.of(currentYear(), c.year()))
                .distinct().sorted(Comparator.reverseOrder()).toList();
        return new Statistics(c.year(), "DETECTED_AT", "Asia/Seoul", c.months(), scopes, c.equipmentIds(), years,
                Arrays.stream(monthly).sum(), months, total, total - unclassified, unclassified, categories);
    }

    ReportPage reports(AuthUser user, CauseStatisticsCondition c) {
        List<String> scopes = scopes(user, c);
        long total = query.count(c, scopes);
        return new ReportPage(c.year(), "DETECTED_AT", "Asia/Seoul", c.months(), scopes, c.equipmentIds(),
                c.categories(), query.reports(c, scopes), c.page(), c.size(), total,
                total / c.size() + (total % c.size() == 0 ? 0 : 1));
    }

    private List<String> scopes(AuthUser user, CauseStatisticsCondition c) {
        if (!user.isManager() && !(user.role() == Role.ENGINEER && "설비정비팀".equals(user.team()))) {
            throw ApiException.forbidden("CAUSE_STATISTICS_ACCESS_DENIED", "관리자 또는 설비정비팀만 원인 통계를 조회할 수 있습니다.");
        }
        List<String> scopes = c.processCodes().isEmpty()
                ? CauseStatisticsCondition.PROCESSES.stream().filter(user::hasScope).toList() : c.processCodes();
        if (scopes.isEmpty()) throw ApiException.forbidden("PROCESS_SCOPE_DENIED", "조회 가능한 공정이 없습니다.");
        scopes.forEach(process -> access.requireScope(user, process));
        if (!c.equipmentIds().isEmpty()) {
            var equipmentScopes = query.equipmentScopes(c.equipmentIds());
            if (!equipmentScopes.stream().map(CauseStatisticsQuery.EquipmentScope::equipmentId).toList()
                    .containsAll(c.equipmentIds())) {
                throw ApiException.badRequest("INVALID_EQUIPMENT_ID", "조회할 수 없는 설비 필터입니다.");
            }
            equipmentScopes.forEach(equipment -> access.requireScope(user, equipment.processCode()));
        }
        return List.copyOf(scopes);
    }
}
