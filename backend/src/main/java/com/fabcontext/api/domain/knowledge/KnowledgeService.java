package com.fabcontext.api.domain.knowledge;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import com.fabcontext.api.auth.AuthUser;
import com.fabcontext.api.common.ApiException;
import com.fabcontext.api.common.Cursors;
import com.fabcontext.api.common.PageResponse;
import com.fabcontext.api.domain.knowledge.KnowledgeDtos.Document;
import com.fabcontext.api.domain.knowledge.KnowledgeDtos.DocumentSearch;
import com.fabcontext.api.domain.knowledge.KnowledgeDtos.SearchHit;
import com.fabcontext.api.domain.knowledge.KnowledgeDtos.SectionRef;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class KnowledgeService {

	public static final String COMMON = "COMMON";

	private final KnowledgeDocumentRepository repository;

	public DocumentSearch search(AuthUser user, String q, String processCode, String docType, String cursor,
			Integer size) {
		if (processCode != null && !COMMON.equals(processCode) && !user.hasScope(processCode)) {
			throw ApiException.forbidden("PROCESS_SCOPE_DENIED", "이 공정(" + processCode + ") 자료에 대한 권한이 없습니다.");
		}
		List<SearchHit> hits = rank(q, processCode, docType).stream()
			.filter(h -> COMMON.equals(h.processCode()) || user.hasScope(h.processCode()))
			.toList();
		PageResponse<SearchHit> page = Cursors.page(hits, cursor, size, h -> h);
		return new DocumentSearch(q, page.items(), page.nextCursor(),
			"score는 검색 유사도이며 원인 확률이 아닙니다. isApprovedProcedure=false 자료는 참고 근거로만 사용하세요.");
	}

	/** Agent 내부 검색 (rag.search_procedures 대체). 권한 필터 없이 공정 코드로만 거른다. */
	public List<SearchHit> searchInternal(String q, String processCode, int limit) {
		return rank(q, null, null).stream()
			.filter(h -> processCode == null || processCode.equals(h.processCode()) || COMMON.equals(h.processCode()))
			.limit(limit)
			.toList();
	}

	public Document get(AuthUser user, String docId, String version) {
		KnowledgeDocument doc = load(user, docId, version);
		List<String> versions = repository.findByDocIdOrderByPublishedAtDesc(docId).stream()
			.map(KnowledgeDocument::getVersion).toList();
		return new Document(doc.getDocId(), doc.getTitle(), doc.getDocType(), doc.getProcessCode(), doc.getVersion(),
			versions, doc.isLatest(), doc.getSourceUrl(), doc.isSynthetic(), doc.resourceUri(),
			doc.isApprovedProcedure(), doc.getSummary(),
			doc.getSections().stream()
				.map(s -> new SectionRef(String.valueOf(s.get("sectionId")), String.valueOf(s.get("heading"))))
				.toList(),
			doc.getPublishedAt());
	}

	public String read(AuthUser user, String docId, String version) {
		KnowledgeDocument doc = load(user, docId, version);
		StringBuilder sb = new StringBuilder();
		sb.append("# ").append(doc.getTitle()).append('\n');
		sb.append("docId: ").append(doc.getDocId()).append(" · version: ").append(doc.getVersion())
			.append(" · ").append(doc.isSynthetic() ? "합성 자료" : "공개 자료")
			.append(" · ").append(doc.isApprovedProcedure() ? "승인 절차" : "참고 자료(승인 절차 아님)").append("\n\n");
		for (Map<String, Object> s : doc.getSections()) {
			sb.append("## ").append(s.get("heading")).append('\n').append(s.get("text")).append("\n\n");
		}
		return sb.toString();
	}

	private KnowledgeDocument load(AuthUser user, String docId, String version) {
		KnowledgeDocument doc = (version == null || version.isBlank()
				? repository.findByDocIdAndLatestTrue(docId)
				: repository.findByDocIdAndVersion(docId, version))
			.orElseThrow(() -> ApiException.notFound("document", version == null ? docId : docId + "@" + version));
		if (!COMMON.equals(doc.getProcessCode()) && !user.hasScope(doc.getProcessCode())) {
			throw ApiException.forbidden("PROCESS_SCOPE_DENIED", "이 공정(" + doc.getProcessCode() + ") 자료에 대한 권한이 없습니다.");
		}
		return doc;
	}

	private List<SearchHit> rank(String q, String processCode, String docType) {
		List<String> terms = q == null ? List.of()
			: Arrays.stream(q.toLowerCase(Locale.ROOT).split("[\\s,·/]+")).filter(t -> t.length() >= 2).toList();
		List<SearchHit> hits = new ArrayList<>();
		for (KnowledgeDocument doc : repository.findByLatestTrue()) {
			if (processCode != null && !processCode.equals(doc.getProcessCode())) {
				continue;
			}
			if (docType != null && !docType.equalsIgnoreCase(doc.getDocType())) {
				continue;
			}
			for (Map<String, Object> s : doc.getSections()) {
				String heading = String.valueOf(s.get("heading"));
				String text = String.valueOf(s.get("text"));
				double score = terms.isEmpty() ? 0.1 : score(terms, doc.getTitle(), heading, text);
				if (score > 0) {
					hits.add(new SearchHit(doc.getDocId(), doc.getTitle(), heading, String.valueOf(s.get("sectionId")),
						snippet(text, terms), score, doc.getVersion(), doc.resourceUri(), doc.isApprovedProcedure(),
						doc.getDocType(), doc.getProcessCode(), doc.isSynthetic()));
				}
			}
		}
		hits.sort(Comparator.comparingDouble(SearchHit::score).reversed().thenComparing(SearchHit::docId));
		return hits;
	}

	private static double score(List<String> terms, String title, String heading, String text) {
		String t = title.toLowerCase(Locale.ROOT);
		String h = heading.toLowerCase(Locale.ROOT);
		String body = text.toLowerCase(Locale.ROOT);
		double raw = 0;
		int matched = 0;
		for (String term : terms) {
			double s = (t.contains(term) ? 1.0 : 0) + (h.contains(term) ? 1.5 : 0) + Math.min(3, count(body, term)) * 0.8;
			if (s > 0) {
				matched++;
			}
			raw += s;
		}
		if (matched == 0) {
			return 0;
		}
		double coverage = (double) matched / terms.size();
		return Math.round(Math.min(1.0, coverage * 0.6 + Math.min(1.0, raw / (terms.size() * 3.0)) * 0.4) * 1000) / 1000.0;
	}

	private static int count(String body, String term) {
		int n = 0;
		for (int i = body.indexOf(term); i >= 0; i = body.indexOf(term, i + term.length())) {
			n++;
		}
		return n;
	}

	private static String snippet(String text, List<String> terms) {
		String lower = text.toLowerCase(Locale.ROOT);
		int at = terms.stream().mapToInt(lower::indexOf).filter(i -> i >= 0).min().orElse(0);
		int start = Math.max(0, at - 40);
		int end = Math.min(text.length(), start + 220);
		return (start > 0 ? "…" : "") + text.substring(start, end) + (end < text.length() ? "…" : "");
	}
}
