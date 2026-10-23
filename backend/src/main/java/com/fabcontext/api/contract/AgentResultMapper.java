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
	public AnalysisResult toResult(Job job, Incident incident, Map<String, Object> agentResult) {
		List<Map<String, Object>> facts = facts(agentResult);
		List<Map<String, Object>> causes = causes(agentResult, incident);
		List<Map<String, Object>> refs = evidenceRefs(agentResult);
		List<String> limitations = new ArrayList<>(new LinkedHashSet<>((List<String>) agentResult.getOrDefault("limitations", List.of())));
		String top = causes.isEmpty() ? "없음" : String.valueOf(causes.getFirst().get("hypothesis"));
		String summary = "Agent " + job.getKind() + " 분석: 관측 사실 " + facts.size() + "건, 가설 " + causes.size()
				+ "건. 우선 확인 대상은 \"" + top + "\"이며 원인은 확정하지 않았습니다.";
		String answer = agentResult.get("answer") instanceof String text ? text : null;
		if (answer != null && !answer.isBlank()) summary = answer;
		return AnalysisResult.builder()
			.resultId(Ids.next("res"))
			.incidentId(incident.getIncidentId())
			.jobId(job.getJobId())
			.threadId(job.getThreadId())
			.kind(job.getKind().name())
			.question(job.getQuestion())
			.answer(answer)
			.agentResultId((String) agentResult.get("resultId"))
			.summary(summary.length() > 1900 ? summary.substring(0, 1900) : summary)
			.observedFacts(facts)
			.candidateCauses(causes)
			.limitations(limitations)
			.evidenceRefs(refs)
			.nextActions(new ArrayList<>())
			.dataVersion(String.valueOf(agentResult.getOrDefault("dataVersion", "UNKNOWN")))
			.createdAt(Instant.now(clock))
			.build();
	}

	public AnalysisResult toResult(Job job, Incident incident, Map<String, Object> result, Map<String, Object> analysis) {
		AnalysisResult mapped = toResult(job, incident, result);
		mapped.getAnalysis().putAll(analysis);
		if (!mapped.getAnalysis().containsKey("resultKind")) {
			boolean changed = analysis.get("conclusion") instanceof Map<?, ?> c && Boolean.TRUE.equals(c.get("changed"));
			String purpose = String.valueOf(job.getInput().get("purpose"));
			boolean cause = job.getKind() == com.fabcontext.api.domain.job.JobKind.BASIC || "FOLLOWUP".equals(purpose)
				|| job.getKind() == com.fabcontext.api.domain.job.JobKind.RESUME || changed;
			mapped.getAnalysis().put("resultKind", cause ? "CAUSE_ANALYSIS" : "ANSWER");
		}
		return mapped;
	}

	/** Agent 계약의 사실은 글(text)과 근거뿐이다 — 숫자 값 · 단위는 지어내지 않고 null로 둔다(글 안에 있다) */
	@SuppressWarnings("unchecked")
	private List<Map<String, Object>> facts(Map<String, Object> agentResult) {
		List<Map<String, Object>> facts = new ArrayList<>();
		for (Map<String, Object> fact : (List<Map<String, Object>>) agentResult.getOrDefault("observedFacts", List.of())) {
			facts.add(shape.fact(facts.size() + 1, (String) fact.get("text"),
				(List<String>) fact.getOrDefault("evidenceIds", List.of()), null, null));
		}
		return facts;
	}

	@SuppressWarnings("unchecked")
	private List<Map<String, Object>> causes(Map<String, Object> agentResult, Incident incident) {
		List<Map<String, Object>> causes = new ArrayList<>();
		for (Map<String, Object> cause : (List<Map<String, Object>>) agentResult.getOrDefault("candidateCauses", List.of())) {
			Object next = cause.get("nextCheck");
			int rank = cause.get("priority") instanceof Number priority ? priority.intValue() : causes.size() + 1;
			Map<String, Object> mapped = shape.cause(rank, (String) cause.get("hypothesis"), incident.getProcessCode(),
				(List<String>) cause.getOrDefault("supportingEvidenceIds", List.of()),
				(List<String>) cause.getOrDefault("conflictingEvidenceIds", List.of()),
				next == null ? List.of() : List.of(next.toString()), (List<String>) cause.getOrDefault("missingData", List.of()),
				(List<String>) cause.getOrDefault("causeIds", List.of()));
			for (String key : List.of("candidateId", "causeIds", "stance"))
				if (cause.containsKey(key)) mapped.put(key, cause.get(key));
			causes.add(mapped);
		}
		return causes;
	}

	@SuppressWarnings("unchecked")
	private static List<Map<String, Object>> evidenceRefs(Map<String, Object> agentResult) {
		List<Map<String, Object>> refs = new ArrayList<>();
		for (Map<String, Object> evidence : (List<Map<String, Object>>) agentResult.getOrDefault("evidenceRefs", List.of())) {
			Map<String, Object> ref = new LinkedHashMap<>();
			ref.put("evidenceId", evidence.get("evidenceId"));
			ref.put("type", evidence.get("sourceType"));
			ref.put("label", evidence.get("sourceId"));
			ref.put("ref", evidence.get("sourceId"));
			refs.add(ref);
		}
		return refs;
	}
}
