package com.fabcontext.api.domain.report;

import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.fabcontext.api.domain.action.IncidentAction;
import com.fabcontext.api.domain.action.IncidentActionRepository;
import com.fabcontext.api.domain.action.VerificationStatus;
import com.fabcontext.api.domain.analysis.AnalysisResult;
import com.fabcontext.api.domain.analysis.AnalysisResultRepository;
import com.fabcontext.api.domain.checkrequest.CheckRequest;
import com.fabcontext.api.domain.checkrequest.CheckRequestRepositories.CheckRequestRepository;
import com.fabcontext.api.domain.checkrequest.CheckRequestRepositories.ReplyRepository;
import com.fabcontext.api.domain.checkrequest.CheckRequestStatus;
import com.fabcontext.api.domain.checkrequest.Reply;
import com.fabcontext.api.domain.incident.Incident;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/** 사건 · 분석 · 요청/회신 · 조치/효과 기록을 모아 보고서 섹션을 만든다. 기록에 없는 내용은 만들지 않는다. */
@Component
@RequiredArgsConstructor
class ReportComposer {

	private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
		.withZone(ZoneId.of("Asia/Seoul"));

	private final AnalysisResultRepository analysisRepository;
	private final CheckRequestRepository requestRepository;
	private final ReplyRepository replyRepository;
	private final IncidentActionRepository actionRepository;

	record Composition(ReportSections sections, List<String> evidenceIds) {
	}

	Composition compose(Incident incident, Report base) {
		Set<String> evidence = new LinkedHashSet<>();

		StringBuilder overview = new StringBuilder();
		overview.append("- 사건: ").append(incident.getDisplayId()).append(" ").append(incident.getTitle()).append('\n')
			.append("- 공정/설비: ").append(incident.getProcessCode()).append(" · ").append(incident.getEquipmentId())
			.append(incident.getChamberId() == null ? "" : " / " + incident.getChamberId()).append('\n')
			.append("- 로트/웨이퍼: ").append(incident.getLotId()).append(" · ").append(String.join(", ", incident.getWaferIds()))
			.append('\n')
			.append("- 감지 시각: ").append(TIME.format(incident.getDetectedAt())).append(" (KST)\n")
			.append("- 현재 상태: ").append(incident.getStatus()).append(" (사건 버전 ").append(incident.getVersion()).append(")\n");
		if (incident.getSummary() != null) {
			overview.append("- 접수 내용: ").append(incident.getSummary()).append('\n');
		}
		if (base != null) {
			overview.append("- 이전 보고서 v").append(base.getVersion()).append("(").append(base.getStatus())
				.append(")를 기준으로 다시 작성한 초안입니다.\n");
		}

		List<AnalysisResult> results = analysisRepository.findByIncidentIdOrderByCreatedAtDesc(incident.getIncidentId());
		StringBuilder investigation = new StringBuilder();
		if (results.isEmpty()) {
			investigation.append("저장된 분석 결과가 없습니다.\n");
		}
		else {
			AnalysisResult latest = results.getFirst();
			investigation.append("최신 분석(").append(latest.getKind()).append(", ").append(TIME.format(latest.getCreatedAt()))
				.append("): ").append(latest.getSummary()).append("\n\n**관측 사실**\n");
			for (Map<String, Object> fact : latest.getObservedFacts()) {
				investigation.append("- ").append(fact.get("statement")).append('\n');
				addAll(evidence, fact.get("evidenceIds"));
			}
			investigation.append("\n**가설 (확정 아님)**\n");
			for (Map<String, Object> cause : latest.getCandidateCauses()) {
				investigation.append("- [").append(cause.get("status")).append("] ").append(cause.get("hypothesis"))
					.append('\n');
				addAll(evidence, cause.get("supportingEvidenceIds"));
			}
		}

		List<CheckRequest> requests = requestRepository.findByIncidentIdOrderByCreatedAtAsc(incident.getIncidentId());
		StringBuilder collab = new StringBuilder();
		if (requests.isEmpty()) {
			collab.append("발송된 확인 요청이 없습니다.\n");
		}
		for (CheckRequest r : requests) {
			collab.append("- 요청(").append(r.getStatus()).append(") → ").append(r.getOwnerUserId()).append(": ")
				.append(r.getQuestion()).append('\n');
			for (Reply reply : replyRepository.findByRequestIdOrderByCreatedAtAsc(r.getRequestId())) {
				collab.append("  - 회신(").append(reply.getAuthorUserId()).append(", ")
					.append(TIME.format(reply.getCreatedAt())).append("): ").append(reply.getFindings()).append('\n');
				evidence.add("ev:reply:" + reply.getReplyId());
				evidence.addAll(reply.getEvidenceIds());
			}
		}

		List<IncidentAction> actions = actionRepository.findByIncidentIdOrderByPerformedAtAsc(incident.getIncidentId());
		StringBuilder act = new StringBuilder();
		if (actions.isEmpty()) {
			act.append("기록된 실제 조치가 없습니다.\n");
		}
		for (IncidentAction a : actions) {
			act.append("- ").append(TIME.format(a.getPerformedAt())).append(" ").append(a.getActionType()).append(" (")
				.append(a.getPerformedBy()).append("): ").append(a.getDescription()).append(" → 효과: ")
				.append(a.getVerificationStatus()).append('\n');
			a.getVerifications().forEach(v -> act.append("  - 확인(").append(v.getOutcome()).append("): ")
				.append(v.getRationale()).append('\n'));
			evidence.addAll(a.getEvidenceIds());
		}

		StringBuilder open = new StringBuilder();
		requests.stream().filter(r -> r.getStatus() == CheckRequestStatus.OPEN)
			.forEach(r -> open.append("- 회신 대기: ").append(r.getQuestion()).append('\n'));
		actions.stream().filter(a -> a.getVerificationStatus() == VerificationStatus.PENDING)
			.forEach(a -> open.append("- 효과 확인 대기: ").append(a.getDescription()).append('\n'));
		actions.stream().filter(a -> a.getVerificationStatus() == VerificationStatus.UNCHANGED
				|| a.getVerificationStatus() == VerificationStatus.INCONCLUSIVE)
			.forEach(a -> open.append("- 효과 미확인(").append(a.getVerificationStatus()).append("): ")
				.append(a.getDescription()).append('\n'));
		if (!results.isEmpty()) {
			results.getFirst().getLimitations().forEach(l -> open.append("- 분석 한계: ").append(l).append('\n'));
		}
		if (open.isEmpty()) {
			open.append("기록상 미해결 항목이 없습니다. 사건 종료는 관리자가 별도로 판단합니다.\n");
		}
		return new Composition(new ReportSections(overview.toString().strip(), investigation.toString().strip(),
			collab.toString().strip(), act.toString().strip(), open.toString().strip()), new ArrayList<>(evidence));
	}

	static String markdown(Report report, Incident incident) {
		StringBuilder md = new StringBuilder();
		md.append("# ").append(report.getTitle()).append("\n\n");
		md.append("> 사건 ").append(incident.getDisplayId()).append(" · 보고서 v").append(report.getVersion())
			.append(" · ").append(report.getStatus()).append(" · revision ").append(report.getContentRevision());
		if (report.getConfirmedAt() != null) {
			md.append(" · 확정 ").append(TIME.format(report.getConfirmedAt())).append(" by ").append(report.getConfirmedBy());
		}
		md.append("\n\n");
		for (Map<String, Object> s : report.getSections()) {
			Object body = s.get("body");
			md.append("## ").append(s.get("title")).append("\n\n").append(body == null ? "" : body).append("\n\n");
		}
		if (!report.getEvidenceIds().isEmpty()) {
			md.append("## 근거 목록\n\n");
			report.getEvidenceIds().forEach(e -> md.append("- `").append(e).append("`\n"));
		}
		return md.toString();
	}

	@SuppressWarnings("unchecked")
	private static void addAll(Set<String> target, Object ids) {
		if (ids instanceof List<?> list) {
			((List<Object>) list).forEach(o -> target.add(String.valueOf(o)));
		}
	}
}
