package com.fabcontext.api.domain;

import java.util.*;
import com.fabcontext.api.domain.processdata.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import static org.assertj.core.api.Assertions.*;

class InvestigationPriorityRulesTest {
	private final PriorityEvidencePolicy policy=new PriorityEvidencePolicy();
	private final String asOf="2026-10-20T02:59:00Z";
	private Map<String,Object> sensor(double duration,int count,boolean recovered) {
		return Map.of("sensorId","head_pressure","evidenceId","E-SENSOR","maxDurationSeconds",duration,"allRecovered",recovered,
			"repetition",Map.of("status","COMPLETE","observedDeviationRunCount",count));
	}
	@Test void highOverridesUnrelatedMissingSensorsAndOosIsIndependentOfOoc() {
		for(String flag:List.of("ooc","oos")) {
			var assessment=InvestigationPriorityRules.assess(List.of(Map.of("status","AVAILABLE",flag,true,"evidenceId","E-MET")),List.of(),List.of(),List.of("TRACE_MISSING"),asOf,policy);
			assertThat(assessment.level()).isEqualTo("HIGH"); assertThat(assessment.provisional()).isFalse();
			assertThat(assessment.evidenceIds()).containsExactly("E-MET");
		}
	}
	@Test void unavailableMetrologyCannotBecomeHigh() {
		var assessment=InvestigationPriorityRules.assess(List.of(Map.of("status","NOT_AVAILABLE_AS_OF","ooc",true)),List.of(),List.of(),List.of("METROLOGY_MISSING"),asOf,policy);
		assertThat(assessment.level()).isEqualTo("MEDIUM"); assertThat(assessment.provisional()).isTrue();
	}
	@ParameterizedTest @CsvSource({"4.999,1,true,LOW,P-LOW-01","5.0,1,true,MEDIUM,P-MEDIUM-01","5.001,1,true,MEDIUM,P-MEDIUM-01",
		"2.7,2,true,MEDIUM,P-MEDIUM-02","2.7,1,false,MEDIUM,P-MEDIUM-04"})
	void persistenceRepetitionAndRecoveryBoundaries(double duration,int count,boolean recovered,String level,String rule) {
		var result=InvestigationPriorityRules.assess(List.of(),List.of(sensor(duration,count,recovered)),List.of(),List.of(),asOf,policy);
		assertThat(result.level()).isEqualTo(level); assertThat(result.ruleIds()).contains(rule);
	}
	@ParameterizedTest @CsvSource({"0.999,false","1.0,true","1.001,true"})
	void onlyRegisteredGroupWithEnoughOverlapTriggersCooccurrence(double overlap,boolean medium) {
		Map<String,Object> group=Map.of("status","COMPLETE","sensorIds",List.of("head_pressure"),"observedMaxOverlapSeconds",overlap);
		var result=InvestigationPriorityRules.assess(List.of(),List.of(sensor(2.7,1,true)),List.of(group),List.of(),asOf,policy);
		assertThat(result.level()).isEqualTo(medium?"MEDIUM":"LOW");
	}
	@Test void insufficientEvidenceIsCompletedProvisionalMediumRatherThanFailure() {
		var result=InvestigationPriorityRules.assess(List.of(),List.of(sensor(2.7,1,true)),List.of(),List.of("REPETITION_INSUFFICIENT"),asOf,policy);
		assertThat(result.status()).isEqualTo("COMPLETED"); assertThat(result.level()).isEqualTo("MEDIUM"); assertThat(result.provisional()).isTrue();
	}
	@Test void multipleUnrelatedSensorsDoNotAutomaticallyMatchRelatedGroupRule() {
		var result=InvestigationPriorityRules.assess(List.of(),List.of(sensor(2.7,1,true),sensor(2.7,1,true)),List.of(),List.of(),asOf,policy);
		assertThat(result.ruleIds()).doesNotContain("P-MEDIUM-03"); assertThat(result.provisional()).isTrue();
	}
}
