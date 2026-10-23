package com.fabcontext.api.domain.report;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.time.LocalDate;

import org.junit.jupiter.api.Test;

class ReportSearchConditionTest {

	static ReportSearchCondition of(String keyword, String category, String parameter, LocalDate from, LocalDate to) {
		return ReportSearchCondition.of("CMP", keyword, category, parameter, from, to, 0, 20);
	}

	@Test
	void processAndPageAreValidated() {
		assertThatThrownBy(() -> ReportSearchCondition.of(null, null, null, null, null, null, 0, 20))
			.hasFieldOrPropertyWithValue("code", "INVALID_PROCESS_CODE");
		assertThatThrownBy(() -> ReportSearchCondition.of("PHOTO", null, null, null, null, null, 0, 20))
			.hasFieldOrPropertyWithValue("code", "INVALID_PROCESS_CODE");
		assertThatThrownBy(() -> ReportSearchCondition.of("CMP", null, null, null, null, null, 0, 101))
			.hasFieldOrPropertyWithValue("code", "INVALID_PAGE_SIZE");
		assertThatThrownBy(() -> ReportSearchCondition.of("CMP", null, null, null, null, null, -1, 20))
			.hasFieldOrPropertyWithValue("code", "INVALID_PAGE_SIZE");
		ReportSearchCondition third = ReportSearchCondition.of("CMP", null, null, null, null, null, 2, 20);
		assertThat(third.offset()).isEqualTo(40);
		assertThat(third.size()).isEqualTo(20);
	}

	@Test
	void keywordIsTrimmedLimitedAndEscapedForLike() {
		assertThat(of("   ", null, null, null, null).titleKeyword()).isNull();
		assertThat(of("   ", null, null, null, null).titleLikePattern()).isNull();
		assertThat(of("  PM3 ", null, null, null, null).titleLikePattern()).isEqualTo("%PM3%");
		// LIKE 기호는 글자 그대로 찾는다
		assertThat(of("50%_off\\", null, null, null, null).titleLikePattern()).isEqualTo("%50\\%\\_off\\\\%");
		assertThat(of("가".repeat(100), null, null, null, null).titleKeyword()).hasSize(100);
		assertThatThrownBy(() -> of("가".repeat(101), null, null, null, null))
			.hasFieldOrPropertyWithValue("code", "INVALID_KEYWORD");
	}

	@Test
	void categoryUnclassifiedAndMachineParameter() {
		assertThat(of(null, "METHOD", null, null, null).category()).isEqualTo(ReportCauseCategory.METHOD);
		assertThat(of(null, "", null, null, null).category()).isNull();
		ReportSearchCondition unclassified = of(null, "UNCLASSIFIED", "head_pressure", null, null);
		assertThat(unclassified.unclassified()).isTrue();
		assertThat(unclassified.category()).isNull();
		assertThat(unclassified.equipmentParameter()).isNull();
		// 장비 파라미터는 Machine일 때만 쓴다
		assertThat(of(null, "MACHINE", "head_pressure", null, null).equipmentParameter()).isEqualTo("head_pressure");
		assertThat(of(null, "METHOD", "head_pressure", null, null).equipmentParameter()).isNull();
		assertThatThrownBy(() -> of(null, "MACHINERY", null, null, null))
			.hasFieldOrPropertyWithValue("code", "INVALID_CAUSE_CATEGORY");
	}

	@Test
	void detectedPeriodUsesKoreanDaysAndIncludesTheEndDay() {
		ReportSearchCondition october = of(null, null, null, LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 31));
		assertThat(october.detectedFrom()).isEqualTo(Instant.parse("2026-09-30T15:00:00Z"));
		assertThat(october.detectedToExclusive()).isEqualTo(Instant.parse("2026-10-31T15:00:00Z"));
		ReportSearchCondition fromOnly = of(null, null, null, LocalDate.of(2026, 10, 15), null);
		assertThat(fromOnly.detectedFrom()).isNotNull();
		assertThat(fromOnly.detectedToExclusive()).isNull();
		assertThatThrownBy(() -> of(null, null, null, LocalDate.of(2026, 10, 31), LocalDate.of(2026, 10, 1)))
			.hasFieldOrPropertyWithValue("code", "INVALID_DETECTED_RANGE");
	}
}
