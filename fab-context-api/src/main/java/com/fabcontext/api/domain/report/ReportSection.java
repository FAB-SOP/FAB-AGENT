package com.fabcontext.api.domain.report;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** 보고서 본문 한 칸 — 공개 API(프론트 계약 SectionInput)의 {key, title, body}. key는 {@link ReportSections#TITLES}의 다섯 개뿐 */
public record ReportSection(@NotBlank @Size(max = 60) String key, @NotBlank @Size(max = 200) String title,
		@Size(max = 20000) String body) {
}
