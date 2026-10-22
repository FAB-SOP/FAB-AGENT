package com.fabcontext.api.simulator;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

import com.fabcontext.api.auth.UserAccount;
import com.fabcontext.api.auth.UserAccountRepository;
import com.fabcontext.api.common.Ids;
import com.fabcontext.api.domain.analysis.AnalysisResult;
import com.fabcontext.api.contract.ResultShape;
import com.fabcontext.api.domain.checkrequest.CheckRequestRepositories.ReplyRepository;
import com.fabcontext.api.domain.checkrequest.Reply;
import com.fabcontext.api.domain.incident.Incident;
import com.fabcontext.api.domain.job.Job;
import com.fabcontext.api.domain.processdata.DataVersion;
import com.fabcontext.api.domain.processdata.ProcessDataRepositories.RouteRunRepository;
import com.fabcontext.api.domain.processdata.ProcessEvidenceService;
import com.fabcontext.api.domain.processdata.RouteRun;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * 시뮬레이터 결과 — 화면 개발 · Spring 흐름 테스트용 <b>대역</b>이다. 원인을 판정하지 않는다(판정 규칙은 fab-agent 한 곳에만 둔다).
 * <ul>
 * <li>관측 사실: 사건 웨이퍼 경로의 run마다 {@link ProcessEvidenceService} 값(출고 평균 · 증착/제거량 · 같은 PM z · 관리한계)을 그대로 옮긴다</li>
 * <li>원인 후보: 「판정하지 않음」 자리 하나(근거 없음 · INSUFFICIENT_EVIDENCE) — 화면이 결과 모양을 그릴 수 있게</li>
 * <li>limitations 맨 앞에 {@value #NOT_ANALYSIS}</li>
 * <li>질문(DEEP)에는 정해진 모양의 확인 요청 제안 하나 — 받는 사람은 공정 순서로 정한다(사건 공정의 바로 앞 공정 담당 엔지니어,
 * 첫 공정이면 같은 공정). 브리프는 고정 예시다</li>
 * </ul>
 * 승인 · 회신 · 재개 · 보고서의 상태 변화는 {@link AgentWorker}가 흉내 낸다.
 */
@Component
@RequiredArgsConstructor
public class AnalysisFactory {

	public static final String NOT_ANALYSIS = "시뮬레이터 결과 — 실제 분석 아님 (원인은 판정하지 않았습니다. 실제 판정은 fab-agent가 합니다)";

	private final RouteRunRepository routeRepository;
	private final ProcessEvidenceService evidence;
	private final ReplyRepository replyRepository;
	private final UserAccountRepository userRepository;
	private final DataVersion dataVersion;
	private final ResultShape shape;
	private final Clock clock;

	public AnalysisResult basic(Incident incident, Job job) {
		return build(incident, job, "BASIC", null, "기본 분석(시뮬레이터)", facts(incident));
	}

	public AnalysisResult deep(Incident incident, Job job) {
		return build(incident, job, "DEEP", job.getQuestion(), "질문 \"" + abbreviate(job.getQuestion(), 60) + "\"(시뮬레이터)",
			facts(incident));
	}

	public AnalysisResult resume(Incident incident, Job job, String replyId) {
		Facts f = facts(incident);
		Optional<Reply> reply = replyId == null ? Optional.empty() : replyRepository.findById(replyId);
		reply.ifPresentOrElse(r -> f.add("담당자 회신(" + r.getAuthorUserId() + "): " + r.getFindings(), null, null,
			f.ref("ev:reply:" + r.getReplyId(), "REPLY", "담당자 회신 (" + r.getAuthorUserId() + ")", "/api/check-requests/" + r.getRequestId())),
			() -> f.limitations.add("연결된 회신을 찾지 못했습니다."));
		return build(incident, job, "RESUME", job.getQuestion(), "회신 반영 재개(시뮬레이터)", f);
	}

	/**
	 * 질문에 붙이는 확인 요청 제안 — 정해진 모양 하나. 현재 공정만 묻는 질문(scope=CURRENT)이거나 받을 사람이 없으면 만들지 않는다.
	 * 받는 사람 · 대상 설비는 공정 순서와 웨이퍼 경로로 정한다(원인 판정이 아니다).
	 */
	public Optional<Map<String, Object>> checkRequestPayload(Incident incident, Job job) {
		if ("CURRENT".equals(job.getScope())) {
			return Optional.empty();
		}
		int here = ProcessEvidenceService.PROCESS_ORDER.indexOf(incident.getProcessCode());
		String target = here > 0 ? ProcessEvidenceService.PROCESS_ORDER.get(here - 1) : incident.getProcessCode();
		Optional<UserAccount> owner = userRepository.findEngineersByProcess(target).stream()
			.filter(u -> !"설비정비팀".equals(u.getTeam())).findFirst();
		if (owner.isEmpty()) {
			return Optional.empty();
		}
		List<RouteRun> route = incident.getWaferIds().isEmpty() ? List.of()
			: routeRepository.findByWaferIdOrderBySequenceNoAsc(incident.getWaferIds().getFirst());
		Optional<RouteRun> run = route.stream().filter(r -> target.equals(r.getProcessCode())).findFirst();
		Map<String, Object> payload = new LinkedHashMap<>();
		payload.put("ownerUserId", owner.get().getUserId());
		payload.put("targetProcessCode", target);
		payload.put("question", incident.getDisplayId() + " 관련 확인 요청(시뮬레이터 예시): " + abbreviate(job.getQuestion(), 200));
		run.ifPresent(r -> payload.put("equipmentId", r.getEquipmentId()));
		payload.put("causeIds", List.of());
		payload.put("brief", exampleBrief());
		payload.put("dueAt", Instant.now(clock).plus(Duration.ofHours(8)).toString());
		payload.put("evidenceIds", run.map(r -> List.of("ev:run:" + r.getRunId())).orElse(List.of()));
		return Optional.of(payload);
	}

	/** 고정 예시 브리프 — 모양만 보여 준다(숫자 · 원인 후보는 넣지 않는다). 실제 브리프는 fab-agent가 도구 결과에서 만든다 */
	static Map<String, Object> exampleBrief() {
		Map<String, Object> b = new LinkedHashMap<>();
		b.put("reason", "시뮬레이터 예시 브리프 — 실제 분석 아님(원인을 판정하지 않았습니다).");
		b.put("deviations", List.of());
		b.put("suspectedCauses", List.of());
		b.put("relatedRecords", List.of());
		b.put("askFor", List.of("해당 run 전후의 정비 · 세정 기록(예시)"));
		return b;
	}

	/** 결과에 붙일 관측 사실 · 근거 · 한계 */
	private final class Facts {

		final List<Map<String, Object>> facts = new ArrayList<>();
		final Map<String, Map<String, Object>> refs = new LinkedHashMap<>();
		final List<String> limitations = new ArrayList<>(List.of(NOT_ANALYSIS));

		void add(String statement, Object value, String unit, String evidenceId) {
			facts.add(shape.fact(facts.size() + 1, statement, List.of(evidenceId), value, unit));
		}

		String ref(String id, String type, String label, String href) {
			Map<String, Object> e = new LinkedHashMap<>();
			e.put("evidenceId", id);
			e.put("type", type);
			e.put("label", label);
			e.put("ref", href);
			refs.putIfAbsent(id, e);
			return id;
		}
	}

	/** 웨이퍼 경로의 run마다 ProcessEvidenceService 값을 그대로 — 사건 공정까지(분석 시점 기준) */
	@SuppressWarnings("unchecked")
	private Facts facts(Incident incident) {
		Facts f = new Facts();
		if (incident.getWaferIds().isEmpty()) {
			f.limitations.add("사건에 연결된 웨이퍼가 없어 계측 근거를 붙이지 않았습니다.");
			return f;
		}
		Instant asOf = evidence.defaultAsOf();
		int current = ProcessEvidenceService.PROCESS_ORDER.indexOf(incident.getProcessCode()) + 1;
		for (RouteRun r : routeRepository.findByWaferIdOrderBySequenceNoAsc(incident.getWaferIds().getFirst())) {
			if (r.getSequenceNo() > current) {
				continue;
			}
			Map<String, Object> ev = evidence.runEvidence(r, asOf);
			if (!Boolean.TRUE.equals(ev.get("available"))) {
				f.limitations.add(r.getRunId() + ": " + ev.get("unavailableReason"));
				continue;
			}
			Map<String, Object> base = (Map<String, Object>) ((Map<String, Object>) ev.get("baselineAmount")).get("samePm");
			Map<String, Object> r1 = (Map<String, Object>) ev.get("r1");
			String id = f.ref("ev:run:" + r.getRunId(), "METROLOGY", r.getProcessCode() + " " + r.getEquipmentId() + " run",
				"/api/incidents/" + incident.getIncidentId() + "/comparisons?scope="
						+ (r.getSequenceNo() == current ? "CURRENT" : "UPSTREAM&processCode=" + r.getProcessCode()));
			f.add(r.getProcessCode() + " " + r.getEquipmentId() + ": 출고 평균 " + fmt(ev.get("outputMeanA")) + " Å, "
					+ ("DEPOSITED".equals(ev.get("amountKind")) ? "증착량 " : "제거량 ") + fmt(ev.get("amountA")) + " Å (같은 PM 정상 "
					+ fmt(base.get("mean")) + " ± " + fmt(base.get("sd")) + ", z=" + ev.get("amountZSamePm") + ") · 관리한계 "
					+ (Boolean.TRUE.equals(r1.get("outside")) ? r1.get("side") : "안"), ev.get("amountA"), "Å", id);
		}
		return f;
	}

	private AnalysisResult build(Incident incident, Job job, String kind, String question, String label, Facts f) {
		Map<String, Object> placeholder = new LinkedHashMap<>(shape.cause(1, "원인을 판정하지 않음 (시뮬레이터 — 실제 판정은 fab-agent)",
			incident.getProcessCode(), List.of(), List.of(), List.of("실제 Agent(fab-agent)로 분석"), List.of(), List.of()));
		return AnalysisResult.builder()
			.resultId(Ids.next("res"))
			.incidentId(incident.getIncidentId())
			.jobId(job.getJobId())
			.threadId(job.getThreadId())
			.kind(kind)
			.question(question)
			.summary(label + " — 관측 사실 " + f.facts.size() + "건(공정 데이터 값 그대로). 시뮬레이터 결과이며 원인은 판정하지 않았습니다.")
			.observedFacts(f.facts)
			.candidateCauses(new ArrayList<>(List.of(placeholder)))
			.limitations(f.limitations.stream().distinct().toList())
			.evidenceRefs(new ArrayList<>(f.refs.values()))
			.nextActions(new ArrayList<>(List.of(action("VIEW_DATA", "센서 원본 · 참고 범위 확인", "P04"),
				action("VIEW_DATA", "계측 · 공정 비교 확인", "P05"))))
			.dataVersion(dataVersion.current())
			.createdAt(Instant.now(clock))
			.build();
	}

	private static Map<String, Object> action(String type, String label, String target) {
		Map<String, Object> a = new LinkedHashMap<>();
		a.put("type", type);
		a.put("label", label);
		a.put("target", target);
		return a;
	}

	private static String fmt(Object v) {
		return v instanceof Number n ? String.format(Locale.ROOT, "%,.1f", n.doubleValue()) : String.valueOf(v);
	}

	private static String abbreviate(String s, int max) {
		return s == null ? "" : s.length() <= max ? s : s.substring(0, max) + "…";
	}
}
