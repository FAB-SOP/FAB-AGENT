package com.fabcontext.api.simulator;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class AnalysisFactoryTest {

	@ParameterizedTest
	@CsvSource(delimiter = '|', value = {
		"이전 공정 영향 확인해줘|true",
		"CVD 쪽 문제야?|true",
		"증착 조건 변화|true",
		"Upstream influence?|true",
		"패드 교체 이력|false",
		"''|false"})
	void detectsUpstreamQuestions(String question, boolean expected) {
		assertThat(AnalysisFactory.mentionsUpstream(question)).isEqualTo(expected);
	}
}
