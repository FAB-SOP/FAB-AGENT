package com.fabcontext.api.domain.report;

import java.time.LocalDate;

import com.fabcontext.api.auth.AuthUser;
import com.fabcontext.api.common.ApiResponse;
import com.fabcontext.api.domain.report.ReportSearchDtos.ReportSlice;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springdoc.core.annotations.ParameterObject;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 보고서 목록 — 공정 · 제목 검색 · 원인 분류 · 장비 파라미터 · 이상 발생 기간을 서버에서 한 번에 조회한다 */
@RestController
@RequiredArgsConstructor
public class ReportSearchController {

	private final ReportSearchService service;

	/** 정렬은 고정(이상 감지일 최신순)이라 Pageable의 sort는 쓰지 않는다 */
	@Tag(name = "reports")
	@Operation(operationId = "searchReports", summary = "보고서 목록 · 제목 검색(페이지 단위)")
	@GetMapping("/api/reports")
	public ApiResponse<ReportSlice> searchReports(@AuthenticationPrincipal AuthUser user,
			@RequestParam(required = false) String processCode, @RequestParam(required = false) String q,
			@RequestParam(required = false) String category, @RequestParam(required = false) String equipmentParameter,
			@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate detectedFrom,
			@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate detectedTo,
			@ParameterObject @PageableDefault(size = ReportSearchCondition.DEFAULT_SIZE) Pageable pageable) {
		ReportSearchCondition condition = ReportSearchCondition.of(processCode, q, category, equipmentParameter,
			detectedFrom, detectedTo, pageable.getPageNumber(), pageable.getPageSize());
		return ApiResponse.of(service.search(user, condition));
	}
}
