package com.fabcontext.api.domain.processdata;

import java.util.*;

/** PM 기준 §3·§6의 적용 순서. 숫자·그룹은 설정으로, 조건 조합은 코드와 테스트로 관리한다. */
public final class InvestigationPriorityRules {
	private static final double EPS=1e-8;
	private InvestigationPriorityRules() {}
	public record Assessment(String status,String level,boolean provisional,List<String> ruleIds,String reason,
		List<String> evidenceIds,List<String> missingData,String policyVersion,String assessmentAsOf,String mode) {}

	public static Assessment assess(List<Map<String,Object>> metrology,List<Map<String,Object>> sensors,
			List<Map<String,Object>> groups,List<String> missing,String asOf,PriorityEvidencePolicy policy) {
		List<String> high=metrology.stream().filter(m->"AVAILABLE".equals(m.get("status")))
			.filter(m->Boolean.TRUE.equals(m.get("ooc")) || Boolean.TRUE.equals(m.get("oos")))
			.map(m->String.valueOf(m.get("evidenceId"))).toList();
		// 이미 확보한 유효한 상 근거는 다른 근거 누락 때문에 낮추지 않는다.
		if(!high.isEmpty()) return result("HIGH",false,List.of("P-HIGH-01"),"공정 경로의 유효 핵심 계측값이 등록 관리한계 또는 제품 규격을 벗어났습니다.",high,missing,asOf,policy);
		if(!missing.isEmpty()) return result(policy.missingPriority(),true,List.of("P-MEDIUM-04"),"필수 근거가 부족하여 중으로 임시 분류했습니다: "+String.join(", ",missing),List.of(),missing,asOf,policy);
		List<String> rules=new ArrayList<>();
		Set<String> evidence=new LinkedHashSet<>();
		for(Map<String,Object> sensor:sensors) {
			if(((Number)sensor.get("maxDurationSeconds")).doubleValue()>=policy.number("persistentSeconds")-EPS) {
				if(!rules.contains("P-MEDIUM-01")) rules.add("P-MEDIUM-01");
				evidence.add(String.valueOf(sensor.get("evidenceId")));
			}
			Map<?,?> repeat=(Map<?,?>)sensor.get("repetition");
			if("COMPLETE".equals(repeat.get("status")) && ((Number)repeat.get("observedDeviationRunCount")).intValue()>=policy.number("repeatMinimumRunCount")) {
				if(!rules.contains("P-MEDIUM-02")) rules.add("P-MEDIUM-02");
				evidence.add(String.valueOf(sensor.get("evidenceId")));
			}
		}
		for(Map<String,Object> group:groups) {
			if("COMPLETE".equals(group.get("status")) && ((Number)group.get("observedMaxOverlapSeconds")).doubleValue()>=policy.number("cooccurrenceMinimumOverlapSeconds")-EPS) {
				if(!rules.contains("P-MEDIUM-03")) rules.add("P-MEDIUM-03");
				for(Map<String,Object> sensor:sensors) if(((List<?>)group.get("sensorIds")).contains(sensor.get("sensorId"))) evidence.add(String.valueOf(sensor.get("evidenceId")));
			}
		}
		if(!rules.isEmpty()) return result("MEDIUM",false,rules,"유효 센서 편차가 지속·반복·등록 관련 센서 동반 조건 중 하나 이상을 충족했습니다.",List.copyOf(evidence),missing,asOf,policy);
		if(sensors.size()==1 && Boolean.TRUE.equals(sensors.getFirst().get("allRecovered")))
			return result("LOW",false,List.of("P-LOW-01"),"경로 계측이 정상이고 단일 센서의 짧은 편차 후 회복했으며 지속·반복·동반 조건이 없습니다.",List.of(String.valueOf(sensors.getFirst().get("evidenceId"))),missing,asOf,policy);
		if(sensors.isEmpty()) return result("LOW",false,List.of("P-LOW-01"),"계측과 센서 근거가 정상입니다. 정책 §6의 기존 사건 재검토에 해당하며 신규 사건 생성 근거가 아닙니다.",List.of(),missing,asOf,policy);
		return result(policy.missingPriority(),true,List.of("P-MEDIUM-04"),"회복 또는 고립된 단일 편차 조건을 확정할 수 없어 중으로 임시 분류했습니다.",List.of(),List.of("LOW_CONDITIONS_NOT_CONFIRMED"),asOf,policy);
	}
	private static Assessment result(String level,boolean provisional,List<String> rules,String reason,List<String> evidence,List<String> missing,String asOf,PriorityEvidencePolicy policy) {
		return new Assessment("COMPLETED",level,provisional,rules,reason,evidence,List.copyOf(missing),policy.version(),asOf,
			policy.enabled()?"DEMO_ACTIVE":"DRAFT_PREVIEW");
	}
}
