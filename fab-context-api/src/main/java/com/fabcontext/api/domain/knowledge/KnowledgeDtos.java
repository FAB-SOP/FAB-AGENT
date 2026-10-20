package com.fabcontext.api.domain.knowledge;

import java.time.Instant;
import java.util.List;

public final class KnowledgeDtos {

	private KnowledgeDtos() {
	}

	/** 검색 결과 한 건 (문서 섹션 단위 발췌). score는 검색 유사도이며 원인 확률이 아니다. */
	public record SearchHit(String docId, String title, String section, String sectionId, String text, double score,
			String version, String resourceUri, boolean isApprovedProcedure, String docType, String processCode,
			boolean synthetic) {
	}

	public record DocumentSearch(String q, List<SearchHit> items, String nextCursor, String note) {
	}

	public record SectionRef(String sectionId, String heading) {
	}

	public record Document(String docId, String title, String docType, String processCode, String version,
			List<String> versions, boolean latest, String sourceUrl, boolean synthetic, String resourceUri,
			boolean isApprovedProcedure, String summary, List<SectionRef> sections, Instant publishedAt) {
	}
}
