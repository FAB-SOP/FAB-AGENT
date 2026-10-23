package com.fabcontext.api.domain.report;

import java.util.List;

import com.fabcontext.api.auth.AccessPolicy;
import com.fabcontext.api.auth.AuthUser;
import com.fabcontext.api.domain.report.ReportSearchDtos.ReportListItem;
import com.fabcontext.api.domain.report.ReportSearchDtos.ReportSlice;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 보고서 목록 조회 — 공정 조회 권한을 확인하고 한 페이지를 돌려준다 */
@Service
@RequiredArgsConstructor
public class ReportSearchService {

	private final AccessPolicy accessPolicy;
	private final ReportSearchQuery query;

	@Transactional(readOnly = true)
	public ReportSlice search(AuthUser user, ReportSearchCondition condition) {
		accessPolicy.requireScope(user, condition.processCode());
		List<ReportListItem> rows = query.findPagePlusOne(condition);
		boolean hasNext = rows.size() > condition.size();
		List<ReportListItem> items = hasNext ? rows.subList(0, condition.size()) : rows;
		return new ReportSlice(List.copyOf(items), condition.page(), condition.size(), hasNext);
	}
}
