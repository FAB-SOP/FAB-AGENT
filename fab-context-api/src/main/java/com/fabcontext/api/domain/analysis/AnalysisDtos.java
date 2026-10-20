package com.fabcontext.api.domain.analysis;

import java.time.Instant;
import java.util.List;
import java.util.Map;

public final class AnalysisDtos {

	private AnalysisDtos() {
	}

	public record HistoryItem(String resultId, String jobId, String kind, String question, String summary,
			Instant createdAt) {

		static HistoryItem from(AnalysisResult r) {
			return new HistoryItem(r.getResultId(), r.getJobId(), r.getKind(), r.getQuestion(), r.getSummary(),
				r.getCreatedAt());
		}
	}

	/** 최신 분석 결과. latest=false로 조회하면 history에 이전 결과 목록을 함께 준다. */
	public record AnalysisResultView(String resultId, String incidentId, String jobId, String threadId, String kind,
			String question, String summary, List<Map<String, Object>> observedFacts,
			List<Map<String, Object>> candidateCauses, List<String> limitations,
			List<Map<String, Object>> evidenceRefs, List<Map<String, Object>> nextActions, String dataVersion,
			Instant createdAt, List<HistoryItem> history) {

		static AnalysisResultView from(AnalysisResult r, List<HistoryItem> history) {
			return new AnalysisResultView(r.getResultId(), r.getIncidentId(), r.getJobId(), r.getThreadId(),
				r.getKind(), r.getQuestion(), r.getSummary(), List.copyOf(r.getObservedFacts()),
				List.copyOf(r.getCandidateCauses()), List.copyOf(r.getLimitations()), List.copyOf(r.getEvidenceRefs()),
				List.copyOf(r.getNextActions()), r.getDataVersion(), r.getCreatedAt(), history);
		}
	}
}
