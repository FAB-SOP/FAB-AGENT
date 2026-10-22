package com.fabcontext.api.contract;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.fabcontext.api.domain.processdata.PackStore;
import com.fabcontext.api.domain.processdata.ProcessEvidenceService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * 분석 결과(P07) 한 칸의 모양 — Agent 결과(AgentEventService)와 시뮬레이터(AnalysisFactory)가 같은 것을 쓴다.
 * <p>
 * 프론트 명세가 제안한 화면용 키와 지금까지의 키를 <b>함께</b> 저장한다. 프론트는 아직 모의 응답으로 화면을 그리고 있어
 * 어느 키를 실제로 읽는지 확인되지 않았기 때문이다(확정되면 옛 키를 뺀다).
 * <ul>
 * <li>observedFacts: label · value · unit · evidenceId (+ factId · statement · evidenceIds)</li>
 * <li>candidateCauses: rank · cause · supportingEvidence · counterEvidence · check · action · verify · sourceIds
 * (+ causeId · priority · hypothesis · processCode · relation · status · supportingEvidenceIds · counterEvidenceIds · nextChecks ·
 * missingData · matrixCauseIds)</li>
 * </ul>
 * Agent 이벤트 계약(candidateCauses additionalProperties=false)은 그대로이고, 바꾸는 것은 Spring이다.
 */
@Component
@RequiredArgsConstructor
public class ResultShape {

	private static final Pattern RUN = Pattern.compile("RUN-[A-Z0-9-]+?-(CVD|ETCH|CMP)\\b");

	private static final Pattern PLAYBOOK = Pattern.compile("E-PB-DEMO-([A-Z_]+)");

	private static final Pattern CHANGE_EQUIPMENT = Pattern.compile("(?:E-CHG-|ev:change:.*?)(CVD|ETCH|CMP)-PM\\d");

	private static final String VERIFY = "조치 뒤 별도 확인 run에서 해당 공정의 제거 · 증착량과 5위치 계측이 정상 변동으로 돌아오는지 확인한다 — "
			+ "확인 run 결과 전에는 해결로 보고하지 않는다";

	private final PackStore pack;

	public Map<String, Object> fact(int seq, String statement, List<String> evidenceIds, Object value, String unit) {
		List<String> ids = evidenceIds == null ? List.of() : List.copyOf(evidenceIds);
		Map<String, Object> f = new LinkedHashMap<>();
		f.put("factId", "f" + seq);
		f.put("statement", statement);
		f.put("evidenceIds", ids);
		f.put("label", statement);
		f.put("value", value);
		f.put("unit", unit);
		f.put("evidenceId", ids.isEmpty() ? null : ids.getFirst());
		return f;
	}

	/**
	 * 원인 후보 한 칸. relation · processCode는 근거가 가리키는 공정으로 정한다 — 근거 ID의 run 공정, 정비 이력의 설비 공정,
	 * 점검 문서 · 원인 ID의 매트릭스 공정 가운데 가장 많이 가리킨 것(같으면 앞 공정). 사건 공정보다 앞이면 UPSTREAM.
	 */
	public Map<String, Object> cause(int rank, String hypothesis, String incidentProcess, List<String> supporting,
			List<String> counter, List<String> nextChecks, List<String> missingData, List<String> causeIds) {
		List<String> support = supporting == null ? List.of() : List.copyOf(supporting);
		List<String> matrix = new ArrayList<>(causeIds == null ? List.of() : causeIds);
		for (String e : support) { // 점검 문서 근거(E-PB-DEMO-ETCH_WALL)는 원인 ID를 가리킨다
			Matcher m = PLAYBOOK.matcher(e);
			if (m.find() && pack.cause(m.group(1)).isPresent() && !matrix.contains(m.group(1))) {
				matrix.add(m.group(1));
			}
		}
		String process = processOf(support, matrix, incidentProcess);
		int here = incidentProcess == null ? -1 : ProcessEvidenceService.PROCESS_ORDER.indexOf(incidentProcess);
		int there = process == null ? -1 : ProcessEvidenceService.PROCESS_ORDER.indexOf(process);
		String relation = process == null ? "UNKNOWN" : there >= 0 && here >= 0 && there < here ? "UPSTREAM" : "CURRENT";
		List<Map<String, Object>> rows = matrix.stream().map(pack::cause).flatMap(java.util.Optional::stream).toList();
		List<String> sources = rows.stream().flatMap(r -> ((List<?>) r.getOrDefault("sourceIds", List.of())).stream())
			.map(String::valueOf).distinct().toList();
		String action = rows.stream().map(r -> (String) r.get("proposedResponse")).filter(Objects::nonNull).findFirst().orElse(null);
		List<String> checks = nextChecks == null ? List.of() : nextChecks.stream().filter(s -> s != null && !s.isBlank()).toList();
		Map<String, Object> c = new LinkedHashMap<>();
		c.put("causeId", com.fabcontext.api.common.Ids.next("cause"));
		c.put("rank", rank);
		c.put("priority", rank);
		c.put("cause", hypothesis);
		c.put("hypothesis", hypothesis);
		c.put("processCode", process == null ? incidentProcess : process);
		c.put("relation", support.isEmpty() ? "UNKNOWN" : relation);
		c.put("status", support.isEmpty() ? "INSUFFICIENT_EVIDENCE" : "NEEDS_CHECK");
		c.put("supportingEvidence", support);
		c.put("supportingEvidenceIds", support);
		c.put("counterEvidence", counter == null ? new ArrayList<>() : new ArrayList<>(counter));
		c.put("counterEvidenceIds", counter == null ? new ArrayList<>() : new ArrayList<>(counter));
		c.put("check", checks.isEmpty() ? null : String.join(" / ", checks));
		c.put("nextChecks", checks);
		c.put("action", action);
		c.put("verify", support.isEmpty() ? null : VERIFY);
		c.put("sourceIds", sources);
		c.put("missingData", missingData == null ? List.of() : List.copyOf(missingData));
		c.put("matrixCauseIds", List.copyOf(matrix));
		return c;
	}

	/** 근거가 가리키는 공정 — 가장 많이 가리킨 것, 같으면 앞 공정 */
	public String processOf(List<String> evidenceIds, List<String> causeIds, String incidentProcess) {
		Map<String, Integer> votes = new LinkedHashMap<>();
		for (String id : causeIds) {
			pack.cause(id).map(r -> String.valueOf(r.get("process"))).filter(ProcessEvidenceService.PROCESS_ORDER::contains)
				.ifPresent(p -> votes.merge(p, 1, Integer::sum));
		}
		for (String e : evidenceIds) {
			Matcher run = RUN.matcher(e);
			Matcher chg = CHANGE_EQUIPMENT.matcher(e);
			if (run.find()) {
				votes.merge(run.group(1), 1, Integer::sum);
			}
			else if (chg.find()) {
				votes.merge(chg.group(1), 1, Integer::sum);
			}
		}
		if (votes.isEmpty()) {
			return null;
		}
		int best = votes.values().stream().max(Integer::compare).orElse(0);
		return new LinkedHashSet<>(ProcessEvidenceService.PROCESS_ORDER).stream()
			.filter(p -> votes.getOrDefault(p, 0) == best).findFirst().orElse(incidentProcess);
	}
}
