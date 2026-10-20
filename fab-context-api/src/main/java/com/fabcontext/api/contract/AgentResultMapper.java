package com.fabcontext.api.contract;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

import com.fabcontext.api.common.Ids;
import com.fabcontext.api.domain.analysis.AnalysisResult;
import com.fabcontext.api.domain.incident.Incident;
import com.fabcontext.api.domain.job.Job;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * Agent 결과 → 화면 결과. Agent 이벤트 계약(candidateCauses additionalProperties=false)은 그대로 받고,
 * 화면 키 · relation · 원인 매트릭스 출처는 {@link ResultShape}가 붙인다.
 */
@Component
@RequiredArgsConstructor
public class AgentResultMapper {

	private final ResultShape shape;
	private final Clock clock;

	/** 팀 계약 AnalysisResult(Agent result 이벤트) → 화면(P07)이 쓰는 결과 모양 */
	@SuppressWarnings("unchecked")
	public AnalysisResult toResult(Job job, Incident incident, Map<String, Object> r) {
		List<Map<String, Object>> facts = new ArrayList<>();
		for (Map<String, Object> f : (List<Map<String, Object>>) r.getOrDefault("observedFacts", List.of())) {
			// Agent 계약의 사실은 글(text)과 근거뿐이다 — 숫자 값 · 단위는 지어내지 않고 null로 둔다(글 안에 있다)
			facts.add(shape.fact(facts.size() + 1, (String) f.get("text"), (List<String>) f.getOrDefault("evidenceIds", List.of()),
				null, null));
		}
		List<Map<String, Object>> causes = new ArrayList<>();
		for (Map<String, Object> c : (List<Map<String, Object>>) r.getOrDefault("candidateCauses", List.of())) {
			Object next = c.get("nextCheck");
			int rank = c.get("priority") instanceof Number n ? n.intValue() : causes.size() + 1;
			causes.add(shape.cause(rank, (String) c.get("hypothesis"), incident.getProcessCode(),
				(List<String>) c.getOrDefault("supportingEvidenceIds", List.of()),
				(List<String>) c.getOrDefault("conflictingEvidenceIds", List.of()),
				next == null ? List.of() : List.of(next.toString()), (List<String>) c.getOrDefault("missingData", List.of()),
				(List<String>) c.getOrDefault("causeIds", List.of())));
		}
		List<Map<String, Object>> refs = new ArrayList<>();
		for (Map<String, Object> e : (List<Map<String, Object>>) r.getOrDefault("evidenceRefs", List.of())) {
			Map<String, Object> m = new LinkedHashMap<>();
			m.put("evidenceId", e.get("evidenceId"));
			m.put("type", e.get("sourceType"));
			m.put("label", e.get("sourceId"));
			m.put("ref", e.get("sourceId"));
			refs.add(m);
		}
		List<String> limitations = new ArrayList<>(new LinkedHashSet<>((List<String>) r.getOrDefault("limitations", List.of())));
		String top = causes.isEmpty() ? "없음" : String.valueOf(causes.getFirst().get("hypothesis"));
		String summary = "Agent " + job.getKind() + " 분석: 관측 사실 " + facts.size() + "건, 가설 " + causes.size()
				+ "건. 우선 확인 대상은 \"" + top + "\"이며 원인은 확정하지 않았습니다.";
		return AnalysisResult.builder()
			.resultId(Ids.next("res"))
			.incidentId(incident.getIncidentId())
			.jobId(job.getJobId())
			.threadId(job.getThreadId())
			.kind(job.getKind().name())
			.question(job.getQuestion())
			.summary(summary.length() > 1900 ? summary.substring(0, 1900) : summary)
			.observedFacts(facts)
			.candidateCauses(causes)
			.limitations(limitations)
			.evidenceRefs(refs)
			.nextActions(new ArrayList<>())
			.dataVersion(String.valueOf(r.getOrDefault("dataVersion", "UNKNOWN")))
			.createdAt(Instant.now(clock))
			.build();
	}
}
