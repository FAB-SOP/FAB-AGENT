package com.fabcontext.api.simulator;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;

import com.fabcontext.api.domain.checkrequest.RequestBrief;
import org.junit.jupiter.api.Test;

/** 시뮬레이터 대역 — 고정 예시 브리프는 브리프 검사를 통과하고 원인 후보 · 숫자를 담지 않는다 */
class AnalysisFactoryTest {

	@Test
	void exampleBriefIsValidAndCarriesNoJudgment() {
		Map<String, Object> brief = RequestBrief.validate(AnalysisFactory.exampleBrief());
		assertThat((String) brief.get("reason")).startsWith("시뮬레이터 예시 브리프");
		assertThat(brief.get("suspectedCauses")).isEqualTo(List.of());
		assertThat(brief.get("deviations")).isEqualTo(List.of());
	}
}
