package com.fabcontext.api.simulator;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

import com.fabcontext.api.auth.Role;
import com.fabcontext.api.auth.UserAccount;
import com.fabcontext.api.auth.UserAccountRepository;
import com.fabcontext.api.common.Ids;
import com.fabcontext.api.domain.analysis.AnalysisResult;
import com.fabcontext.api.domain.checkrequest.CheckRequestRepositories.ReplyRepository;
import com.fabcontext.api.domain.checkrequest.Reply;
import com.fabcontext.api.domain.incident.Incident;
import com.fabcontext.api.domain.job.Job;
import com.fabcontext.api.domain.knowledge.KnowledgeDtos.SearchHit;
import com.fabcontext.api.domain.knowledge.KnowledgeService;
import com.fabcontext.api.domain.processdata.ChangeRecord;
import com.fabcontext.api.domain.processdata.DataVersion;
import com.fabcontext.api.domain.processdata.PackStore;
import com.fabcontext.api.domain.processdata.ProcessDataRepositories.ChangeRecordRepository;
import com.fabcontext.api.domain.processdata.ProcessDataRepositories.RouteRunRepository;
import com.fabcontext.api.domain.processdata.ProcessEvidenceService;
import com.fabcontext.api.domain.processdata.RouteRun;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * 시뮬레이션 Agent의 규칙 분석 — 진짜 Agent(fab-agent)가 없을 때 화면을 돌리기 위한 대역이다.
 * 판정 순서는 fab-agent의 규칙 모델과 같다:
 * <ol>
 * <li>사건 공정의 제거량이 정상 변동 밖이고 그 run의 센서가 참고 범위를 벗어나면 → 현재 공정 후보(원인 매트릭스의 센서 매핑)</li>
 * <li>앞 공정 run의 제거량만 정상 변동 밖이면 → 그 공정의 기여 후보(부품 원인은 미확인) · 담당자 확인 요청</li>
 * <li>둘 다 아니면 → 근거 부족, 관찰</li>
 * </ol>
 * 원인 확률을 만들지 않고, 분석 시점 이후 자료와 FOLLOWUP은 쓰지 않는다.
 */
@Component
@RequiredArgsConstructor
public class AnalysisFactory {

	private static final List<String> UPSTREAM_KEYWORDS = List.of("이전 공정", "이전공정", "앞 공정", "upstream", "cvd",
			"증착", "etch", "식각");

	private final RouteRunRepository routeRepository;
	private final ChangeRecordRepository changeRepository;
	private final ProcessEvidenceService evidence;
	private final PackStore pack;
	private final KnowledgeService knowledgeService;
	private final ReplyRepository replyRepository;
	private final UserAccountRepository userRepository;
	private final DataVersion dataVersion;
	private final com.fabcontext.api.domain.analysis.ResultShape shape;
	private final Clock clock;

	/** 분석에 쓰는 근거 묶음 */
	private final class Context {

		final List<Map<String, Object>> facts = new ArrayList<>();
		final List<Map<String, Object>> causes = new ArrayList<>();
		final List<String> limitations = new ArrayList<>();
		final Map<String, Map<String, Object>> evidence = new LinkedHashMap<>();
		final List<Map<String, Object>> nextActions = new ArrayList<>();
		String incidentProcess;
		String upstreamProcess;
		String upstreamEquipment;
		String upstreamRunId;
		List<String> upstreamCauseIds = List.of();
		/** 요청 브리프용 — 단계별 증착 · 제거량 편차와 앞 공정 설비의 정비 이력 (숫자는 ProcessEvidenceService 그대로) */
		final List<Map<String, Object>> deviations = new ArrayList<>();
		final List<Map<String, Object>> relatedRecords = new ArrayList<>();
		String currentEquipment;
		String waferId;

		void fact(String statement, String... evidenceIds) {
			factValue(statement, null, null, evidenceIds);
		}

		/** 관측 사실 — 화면용 label · value · unit · evidenceId와 옛 키를 함께(ResultShape) */
		void factValue(String statement, Object value, String unit, String... evidenceIds) {
			facts.add(shape.fact(facts.size() + 1, statement, List.of(evidenceIds), value, unit));
		}

		String evidence(String id, String type, String label, String ref) {
			Map<String, Object> e = new LinkedHashMap<>();
			e.put("evidenceId", id);
			e.put("type", type);
			e.put("label", label);
			e.put("ref", ref);
			evidence.putIfAbsent(id, e);
			return id;
		}
	}

	public AnalysisResult basic(Incident incident, Job job) {
		Context ctx = collect(incident);
		return build(incident, job, "BASIC", null, summarize(ctx, "기본 분석"), ctx);
	}

	public AnalysisResult deep(Incident incident, Job job) {
		Context ctx = collect(incident);
		String question = job.getQuestion();
		for (SearchHit hit : knowledgeService.searchInternal(question, incident.getProcessCode(), 3)) {
			String id = ctx.evidence("ev:doc:" + hit.docId() + "@" + hit.version(), "DOCUMENT",
				hit.title() + " · " + hit.section(), hit.resourceUri());
			ctx.fact("관련 자료: " + hit.title() + " (" + (hit.isApprovedProcedure() ? "승인 절차" : "참고 자료, 승인 절차 아님")
					+ ", 검색 유사도 " + hit.score() + ")", id);
		}
		if (ctx.upstreamProcess != null || "UPSTREAM".equals(job.getScope()) || mentionsUpstream(question)) {
			ctx.nextActions.add(action("PROPOSE_CHECK_REQUEST", "이전 공정 담당자 확인 요청", ctx.upstreamProcess));
		}
		String summary = "질문 \"" + abbreviate(question, 60) + "\"에 대해 " + summarize(ctx, "추가 분석");
		return build(incident, job, "DEEP", question, summary, ctx);
	}

	public AnalysisResult resume(Incident incident, Job job, String replyId) {
		Context ctx = collect(incident);
		Optional<Reply> reply = replyId == null ? Optional.empty() : replyRepository.findById(replyId);
		reply.ifPresent(r -> {
			String id = ctx.evidence("ev:reply:" + r.getReplyId(), "REPLY", "담당자 회신 (" + r.getAuthorUserId() + ")",
				"/api/check-requests/" + r.getRequestId());
			ctx.fact("담당자 회신(" + r.getAuthorUserId() + "): " + r.getFindings(), id);
			for (Map<String, Object> cause : ctx.causes) {
				if ("UPSTREAM".equals(cause.get("relation"))) {
					cause.put("status", "OWNER_REPLIED");
					cause.put("ownerStatementIds", List.of(id));
				}
			}
		});
		if (reply.isEmpty()) {
			ctx.limitations.add("연결된 회신을 찾지 못해 이전 분석 기준으로 재개했습니다.");
		}
		ctx.limitations.add("담당자 회신은 진술 근거이며, 원본 데이터로 교차 확인 전에는 원인 확정 근거로 쓰지 않습니다.");
		String summary = "담당자 회신을 반영해 조사를 재개했습니다. " + summarize(ctx, "재개 분석");
		return build(incident, job, "RESUME", job.getQuestion(), summary, ctx);
	}

	/** 앞 공정 기여 후보가 있으면(또는 질문이 앞 공정을 물으면) 그 공정 담당자에게 확인 요청을 제안한다 */
	@SuppressWarnings("unchecked")
	public Optional<Map<String, Object>> upstreamCheckPayload(Incident incident, Job job) {
		if ("CURRENT".equals(job.getScope())) {
			return Optional.empty(); // 현재 공정만 묻는 질문에는 앞 공정 요청을 만들지 않는다
		}
		Context ctx = collect(incident);
		if (ctx.upstreamProcess == null && !"UPSTREAM".equals(job.getScope()) && !mentionsUpstream(job.getQuestion())) {
			return Optional.empty();
		}
		String target = ctx.upstreamProcess;
		if (target == null) {
			return Optional.empty();
		}
		Optional<UserAccount> owner = userRepository.findAll().stream()
			.filter(u -> u.getRole() == Role.ENGINEER && u.getProcessScopes().contains(target))
			.filter(u -> !"설비정비팀".equals(u.getTeam())).findFirst();
		if (owner.isEmpty()) {
			return Optional.empty();
		}
		Map<String, Object> payload = new LinkedHashMap<>();
		payload.put("ownerUserId", owner.get().getUserId());
		payload.put("targetProcessCode", target);
		Map<String, Object> up = ctx.deviations.stream().filter(d -> ctx.upstreamRunId.equals(d.get("runId"))).findFirst()
			.orElse(Map.of());
		String reason = ctx.waferId + "의 " + ctx.upstreamEquipment + " " + metricLabel(up) + " " + fmt(up.get("value")) + " Å가 같은 PM 정상 "
				+ "평균 " + fmt(up.get("baselineMean")) + " Å 대비 z=" + up.get("z") + "로 정상 변동(|z| < " + up.get("threshold") + ") 밖입니다. "
				+ "현재 공정 " + ctx.currentEquipment + " " + incident.getProcessCode() + " 후 두께가 관리한계를 벗어나 앞 공정 기여를 확인하려 합니다.";
		payload.put("question", incident.getDisplayId() + " 관련: " + reason + " " + ctx.upstreamEquipment
				+ "에서 처리된 이 run 전후의 정비 · 시즈닝 · 모니터 결과를 확인 부탁드립니다.");
		payload.put("equipmentId", ctx.upstreamEquipment);
		payload.put("causeIds", ctx.upstreamCauseIds);
		payload.put("brief", brief(reason, ctx));
		payload.put("dueAt", Instant.now(clock).plus(Duration.ofHours(8)).toString());
		List<String> ids = new ArrayList<>();
		ctx.causes.stream().filter(c -> "UPSTREAM".equals(c.get("relation")))
			.forEach(c -> ids.addAll((List<String>) c.get("supportingEvidenceIds")));
		payload.put("evidenceIds", List.copyOf(new LinkedHashSet<>(ids)));
		return Optional.of(payload);
	}

	/** 요청 브리프 — 숫자 · 원인 ID · 근거는 계산 결과와 원인 매트릭스에서 옮기고, 원인은 확정하지 않는다(confirmed=false) */
	@SuppressWarnings("unchecked")
	private Map<String, Object> brief(String reason, Context ctx) {
		Map<String, Map<String, Object>> matrix = new LinkedHashMap<>();
		pack.causes().forEach(c -> matrix.put(String.valueOf(c.get("causeId")), c));
		List<Map<String, Object>> suspected = new ArrayList<>();
		List<String> askFor = new ArrayList<>(List.of("해당 run 전후 정비 · 세정 · 시즈닝 기록과 절차(매수 포함)", "시즈닝 후 모니터 웨이퍼 결과"));
		for (String id : ctx.upstreamCauseIds) {
			Map<String, Object> c = matrix.getOrDefault(id, Map.of());
			Map<String, Object> s = new LinkedHashMap<>();
			s.put("causeId", id);
			s.put("candidate", c.get("candidate"));
			s.put("confirmed", false);
			s.put("evidenceToCheck", c.get("evidenceToCheck"));
			s.put("limits", c.get("limits"));
			suspected.add(s);
			if (c.get("evidenceToCheck") != null) {
				askFor.add(String.valueOf(c.get("evidenceToCheck")));
			}
		}
		Map<String, Object> b = new LinkedHashMap<>();
		b.put("reason", reason);
		b.put("deviations", List.copyOf(ctx.deviations));
		b.put("suspectedCauses", suspected);
		b.put("relatedRecords", List.copyOf(ctx.relatedRecords));
		b.put("askFor", askFor);
		return b;
	}

	/** run 하나의 증착 · 제거량 편차 — 같은 PM 정상(BASELINE) 분포 대비 z와 판정 기준을 함께 */
	private static Map<String, Object> amountDeviation(Map<String, Object> ev, Map<String, Object> base) {
		Map<String, Object> d = new LinkedHashMap<>();
		d.put("runId", ev.get("runId"));
		d.put("equipmentId", ev.get("equipmentId"));
		d.put("processCode", ev.get("processCode"));
		d.put("metric", "DEPOSITED".equals(ev.get("amountKind")) ? "depositedA" : "removedA");
		d.put("value", ev.get("amountA"));
		d.put("unit", "Å");
		d.put("baselineMean", base == null ? null : base.get("mean"));
		d.put("z", ev.get("amountZSamePm"));
		d.put("threshold", ev.get("normalVariationSigma"));
		d.put("thresholdKind", "ABS_Z_SAME_PM");
		d.put("withinNormal", ev.get("amountWithinNormalVariation"));
		return d;
	}

	private static String metricLabel(Map<String, Object> d) {
		return "depositedA".equals(d.get("metric")) ? "증착량" : "제거량";
	}

	public static boolean mentionsUpstream(String text) {
		if (text == null) {
			return false;
		}
		String lower = text.toLowerCase(Locale.ROOT);
		return UPSTREAM_KEYWORDS.stream().anyMatch(lower::contains);
	}

	@SuppressWarnings("unchecked")
	private Context collect(Incident incident) {
		Context ctx = new Context();
		ctx.incidentProcess = incident.getProcessCode();
		Instant asOf = evidence.defaultAsOf();
		if (incident.getWaferIds().isEmpty()) {
			ctx.limitations.add("사건에 연결된 웨이퍼가 없어 계측 · 센서 근거를 사용할 수 없습니다.");
			collectChanges(incident, ctx, null);
			finish(incident, ctx);
			return ctx;
		}
		List<RouteRun> runs = routeRepository.findByWaferIdOrderBySequenceNoAsc(incident.getWaferIds().getFirst());
		ctx.waferId = incident.getWaferIds().getFirst();
		int current = ProcessEvidenceService.PROCESS_ORDER.indexOf(incident.getProcessCode()) + 1;
		Map<String, Object> currentEv = null;
		List<String> currentSupport = new ArrayList<>();
		List<String> currentCauseIds = new ArrayList<>();
		for (RouteRun r : runs) {
			Map<String, Object> ev = evidence.runEvidence(r, asOf);
			String id = ctx.evidence("ev:run:" + r.getRunId(), "METROLOGY", r.getProcessCode() + " " + r.getEquipmentId() + " run",
				"/api/incidents/" + incident.getIncidentId() + "/comparisons?scope="
						+ (r.getSequenceNo() == current ? "CURRENT" : "UPSTREAM&processCode=" + r.getProcessCode()));
			if (!Boolean.TRUE.equals(ev.get("available"))) {
				ctx.limitations.add(r.getRunId() + ": " + ev.get("unavailableReason"));
				continue;
			}
			Map<String, Object> base = (Map<String, Object>) ((Map<String, Object>) ev.get("baselineAmount")).get("samePm");
			Map<String, Object> r1 = (Map<String, Object>) ev.get("r1");
			ctx.deviations.add(amountDeviation(ev, base));
			ctx.factValue(r.getProcessCode() + " " + r.getEquipmentId() + ": 출고 평균 " + fmt(ev.get("outputMeanA")) + " Å, "
					+ ("DEPOSITED".equals(ev.get("amountKind")) ? "증착량 " : "제거량 ") + fmt(ev.get("amountA")) + " Å (같은 PM 정상 "
					+ fmt(base.get("mean")) + " ± " + fmt(base.get("sd")) + ", z=" + ev.get("amountZSamePm") + ") · 관리한계 "
					+ (Boolean.TRUE.equals(r1.get("outside")) ? r1.get("side") : "안"), ev.get("amountA"), "Å", id);
			boolean abnormal = !Boolean.TRUE.equals(ev.get("amountWithinNormalVariation"));
			if (r.getSequenceNo() == current) {
				currentEv = ev;
				ctx.currentEquipment = r.getEquipmentId();
				if (abnormal) {
					currentSupport.add(id);
				}
				for (Map<String, Object> f : evidence.featureEvidence(r, null)) {
					if (Boolean.TRUE.equals(f.get("outsideReferenceBand"))) {
						String fid = ctx.evidence("ev:feature:" + r.getRunId() + ":" + f.get("sensorId"), "SENSOR",
							f.get("sensorId") + " 본 처리 평균", "/api/incidents/" + incident.getIncidentId() + "/sensor-data?sensorId="
									+ f.get("sensorId"));
						ctx.factValue(f.get("sensorId") + " 본 처리 평균 " + f.get("mean") + " " + f.get("unit") + " (설정 " + f.get("setpoint")
								+ ") — 정상 참고 범위 밖", f.get("mean"), (String) f.get("unit"), fid);
						currentSupport.add(fid);
						currentCauseIds.addAll(causesFor(r.getProcessCode(), (String) f.get("sensorId")));
					}
				}
			}
			else if (r.getSequenceNo() < current && abnormal && ctx.upstreamProcess == null) {
				ctx.upstreamProcess = r.getProcessCode();
				ctx.upstreamEquipment = r.getEquipmentId();
				ctx.upstreamRunId = r.getRunId();
				ctx.upstreamCauseIds = List.of("ETCH".equals(r.getProcessCode()) ? "ETCH_WALL" : r.getProcessCode() + "_FILM");
				List<String> support = new ArrayList<>(List.of(id));
				collectChanges(incident, ctx, r.getEquipmentId()).forEach(support::add);
				List<String> counter = currentEv == null ? List.of() : List.of();
				ctx.causes.add(cause(ctx, r.getProcessCode() + " " + r.getEquipmentId() + " 제거량 변화의 기여 (앞 공정, 부품 원인 미확인)",
					r.getProcessCode(), "UPSTREAM", support, counter, List.of(r.getProcessCode() + " 담당자에게 해당 run의 제거량 · "
							+ "정비/시즈닝 · 모니터 결과 확인 요청"), ctx.upstreamCauseIds));
			}
		}
		if (!currentSupport.isEmpty()) {
			ctx.causes.addFirst(cause(ctx, "현재 공정 " + incident.getEquipmentId() + "의 제어 · 공급 계통 변화 후보", incident.getProcessCode(),
				"CURRENT", currentSupport, List.of(), List.of("해당 계통의 실제값/설정값 · 공급 · 교정 이력 점검 (승인 SOP 범위)"),
				currentCauseIds.isEmpty() ? List.of() : List.copyOf(new LinkedHashSet<>(currentCauseIds))));
		}
		else if (currentEv != null && !ctx.causes.isEmpty()) {
			// 현재 공정 제거량이 정상 변동 안이면 현재 공정 고장 가설의 반대 근거
			((List<String>) ctx.causes.getFirst().get("counterEvidenceIds")).add("ev:run:" + currentEv.get("runId"));
			((List<String>) ctx.causes.getFirst().get("counterEvidence")).add("ev:run:" + currentEv.get("runId"));
		}
		finish(incident, ctx);
		return ctx;
	}

	private void finish(Incident incident, Context ctx) {
		if (ctx.causes.isEmpty()) {
			ctx.causes.add(cause(ctx, "원인 후보를 좁힐 근거가 부족합니다 (정상 변동 · 관찰)", incident.getProcessCode(), "UNKNOWN", List.of(),
				List.of(), List.of("같은 PM의 후속 run · 추가 이력 확인"), List.of()));
		}
		for (int i = 0; i < ctx.causes.size(); i++) {
			ctx.causes.get(i).put("priority", i + 1);
			ctx.causes.get(i).put("rank", i + 1);
		}
		ctx.limitations.add("원인 순위는 증거 기반 우선순위이며 확률이 아닙니다. 5위치 계측만으로 막질 · 표면 형상은 판정하지 않습니다.");
		ctx.nextActions.add(action("VIEW_DATA", "센서 원본 · 참고 범위 확인", "P04"));
		ctx.nextActions.add(action("VIEW_DATA", "계측 · 공정 비교 확인", "P05"));
		ctx.nextActions.add(action("SEARCH_DOCUMENTS", "관련 점검 문서 · 가상 사례 검색", "P12"));
	}

	private List<String> collectChanges(Incident incident, Context ctx, String equipmentId) {
		List<String> ids = new ArrayList<>();
		List<String> eq = equipmentId != null ? List.of(equipmentId) : List.of(incident.getEquipmentId());
		for (ChangeRecord c : changeRepository.findByEquipmentIdInOrderByOccurredAtDesc(eq)) {
			if (c.getRecordedAt() != null && c.getRecordedAt().isAfter(evidence.defaultAsOf())) {
				continue;
			}
			String id = ctx.evidence("ev:change:" + c.getChangeId(), "CHANGE", c.getEquipmentId() + " " + c.getChangeType(),
				"/api/incidents/" + incident.getIncidentId() + "/changes");
			ctx.fact(c.getOccurredAt() + " " + c.getEquipmentId() + " " + c.getChangeType() + ": " + c.getSummary()
					+ (c.isRootCauseConfirmed() ? "" : " (원인 확인된 기록 아님)"), id);
			ids.add(id);
			if (equipmentId != null) {
				Map<String, Object> rec = new LinkedHashMap<>();
				rec.put("evidenceId", id);
				rec.put("summary", ProcessEvidenceService.kst(c.getOccurredAt()) + " " + c.getEquipmentId() + " " + c.getChangeType() + ": "
						+ c.getSummary());
				rec.put("rootCauseConfirmed", c.isRootCauseConfirmed());
				ctx.relatedRecords.add(rec);
			}
		}
		return ids;
	}

	@SuppressWarnings("unchecked")
	private List<String> causesFor(String process, String sensorId) {
		return pack.causes().stream().filter(c -> process.equals(c.get("process"))
				&& ((List<String>) c.getOrDefault("sensorIds", List.of())).contains(sensorId))
			.map(c -> String.valueOf(c.get("causeId"))).toList();
	}

	/** 원인 후보 — relation · processCode는 ResultShape가 근거 · 원인 ID로 정한다(여기서 넘기는 값은 확인용 기록) */
	private Map<String, Object> cause(Context ctx, String hypothesis, String processCode, String relation, List<String> supporting,
			List<String> counter, List<String> nextChecks, List<String> causeIds) {
		return new LinkedHashMap<>(shape.cause(0, hypothesis, ctx.incidentProcess, supporting, counter, nextChecks, List.of(),
			causeIds));
	}

	private String summarize(Context ctx, String label) {
		long withEvidence = ctx.causes.stream()
			.filter(c -> !((List<?>) c.get("supportingEvidenceIds")).isEmpty()).count();
		String top = ctx.causes.isEmpty() ? "없음" : String.valueOf(ctx.causes.getFirst().get("hypothesis"));
		return label + " 완료: 관측 사실 " + ctx.facts.size() + "건, 근거가 있는 가설 " + withEvidence
				+ "건. 우선 확인 대상은 \"" + top + "\"이며 원인은 확정하지 않았습니다.";
	}

	private AnalysisResult build(Incident incident, Job job, String kind, String question, String summary, Context ctx) {
		return AnalysisResult.builder()
			.resultId(Ids.next("res"))
			.incidentId(incident.getIncidentId())
			.jobId(job.getJobId())
			.threadId(job.getThreadId())
			.kind(kind)
			.question(question)
			.summary(summary)
			.observedFacts(ctx.facts)
			.candidateCauses(ctx.causes)
			.limitations(ctx.limitations.stream().distinct().toList())
			.evidenceRefs(new ArrayList<>(ctx.evidence.values()))
			.nextActions(ctx.nextActions)
			.dataVersion(dataVersion.current())
			.createdAt(Instant.now(clock))
			.build();
	}

	private static Map<String, Object> action(String type, String label, String target) {
		Map<String, Object> a = new LinkedHashMap<>();
		a.put("type", type);
		a.put("label", label);
		a.put("target", Objects.requireNonNullElse(target, ""));
		return a;
	}

	private static String fmt(Object v) {
		return v instanceof Number n ? String.format(Locale.ROOT, "%,.1f", n.doubleValue()) : String.valueOf(v);
	}

	private static String abbreviate(String s, int max) {
		return s == null ? "" : s.length() <= max ? s : s.substring(0, max) + "…";
	}
}
