package com.fabcontext.api.domain.report;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.fabcontext.api.common.ApiException;
import com.fabcontext.api.common.Json;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * 보고서 본문의 다섯 키 — Agent 초안(REPORT_DRAFT_READY, 팀 계약)과 내부 API는 이 객체 모양을 쓴다.
 * 공개 API · DB는 프론트 계약대로 배열 [{key, title, body}](순서 고정)이다 — {@link #toList()} · {@link #validate(List)}.
 */
public record ReportSections(
		@NotNull @Size(max = 20000) String overview,
		@NotNull @Size(max = 20000) String investigation,
		@NotNull @Size(max = 20000) String requestsAndReplies,
		@NotNull @Size(max = 20000) String actionsAndVerification,
		@NotNull @Size(max = 20000) String unresolved) {

	/** 키 → 화면 · Markdown 제목 (순서 고정) */
	public static final Map<String, String> TITLES;

	static {
		Map<String, String> t = new LinkedHashMap<>();
		t.put("overview", "사건 개요");
		t.put("investigation", "조사 내용");
		t.put("requestsAndReplies", "확인 요청 · 회신");
		t.put("actionsAndVerification", "조치 · 효과");
		t.put("unresolved", "미해결 항목");
		TITLES = java.util.Collections.unmodifiableMap(t);
	}

	Map<String, Object> toMap() {
		Map<String, Object> m = new LinkedHashMap<>();
		m.put("overview", overview);
		m.put("investigation", investigation);
		m.put("requestsAndReplies", requestsAndReplies);
		m.put("actionsAndVerification", actionsAndVerification);
		m.put("unresolved", unresolved);
		return m;
	}

	static ReportSections from(Map<String, Object> m) {
		return Json.convert(m, ReportSections.class);
	}

	/** 기본 제목을 붙인 배열(DB · 공개 API 모양) */
	List<ReportSection> toList() {
		List<ReportSection> out = new ArrayList<>();
		toMap().forEach((k, v) -> out.add(new ReportSection(k, TITLES.get(k), String.valueOf(v))));
		return out;
	}

	/** 화면 편집 검사 — 다섯 키가 정해진 순서로 한 번씩만. 제목은 사용자가 바꿀 수 있다 */
	static List<ReportSection> validate(List<ReportSection> sections) {
		List<String> keys = sections == null ? List.of() : sections.stream().map(ReportSection::key).toList();
		if (!keys.equals(List.copyOf(TITLES.keySet()))) {
			throw ApiException.unprocessable("INVALID_SECTIONS", "보고서 sections는 " + TITLES.keySet() + " 다섯 칸이 이 순서여야 합니다. 받은 key: "
					+ keys);
		}
		return sections.stream().map(s -> new ReportSection(s.key(), s.title(), s.body() == null ? "" : s.body())).toList();
	}

	/** 배열 → 다섯 키 객체(내부 API · Agent가 읽는 모양) */
	static Map<String, Object> toMap(List<Map<String, Object>> stored) {
		Map<String, Object> m = new LinkedHashMap<>();
		for (Map<String, Object> s : stored) {
			m.put(String.valueOf(s.get("key")), s.get("body"));
		}
		return m;
	}
}
