package com.fabcontext.api.seed;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.fabcontext.api.auth.Role;
import com.fabcontext.api.auth.UserAccount;
import com.fabcontext.api.auth.UserAccountRepository;
import com.fabcontext.api.domain.knowledge.KnowledgeDocument;
import com.fabcontext.api.domain.knowledge.KnowledgeDocumentRepository;
import com.fabcontext.api.domain.processdata.ChangeRecord;
import com.fabcontext.api.domain.processdata.Metrology;
import com.fabcontext.api.domain.processdata.PackStore;
import com.fabcontext.api.domain.processdata.ProcessDataRepositories.ChangeRecordRepository;
import com.fabcontext.api.domain.processdata.ProcessDataRepositories.MetrologyRepository;
import com.fabcontext.api.domain.processdata.ProcessDataRepositories.RouteRunRepository;
import com.fabcontext.api.domain.processdata.ProcessDataRepositories.RunFeatureRepository;
import com.fabcontext.api.domain.processdata.ProcessEvidenceService;
import com.fabcontext.api.domain.processdata.RouteRun;
import com.fabcontext.api.domain.processdata.RunFeature;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

/**
 * 시드 — 동료 납품 묶음의 데이터 팩(fab-causal-pack-v1)과 실제 원문 RAG(fab-rag-real-documents-v1)를 그대로 DB에 옮긴다.
 * 값을 만들거나 고치지 않는다.
 * <ul>
 * <li>data/: run 1,017개(BASELINE · CURRENT · FOLLOWUP) · 5위치 계측 · run 센서 특징 · 정비 이력</li>
 * <li>지식: 실제 원문 조각 · 팀 점검 문서 · 가상 과거 사례 → 화면 P12 문서</li>
 * </ul>
 * 원시 시계열과 관리한계 · 참고 범위는 DB에 넣지 않고 PackStore가 읽는다. evaluation/은 읽지 않는다.
 */
@Component
@RequiredArgsConstructor
class SeedCatalog {

	static final String PASSWORD = "fab1234!";
	static final String CVD_ENGINEER = "USR-CVD-01";
	static final String ETCH_ENGINEER = "USR-ETCH-01";
	static final String CMP_ENGINEER = "USR-CMP-01";
	static final String MANAGER = "USR-MGR-01";
	static final String MAINTENANCE = "USR-MNT-01";

	private static final Map<String, String> STEP = Map.of("CVD", "PECVD 산화막 증착", "ETCH", "플라스마 산화막 Etchback",
			"CMP", "고정 시간 산화막 CMP");

	private final UserAccountRepository userRepository;
	private final KnowledgeDocumentRepository documentRepository;
	private final RouteRunRepository routeRepository;
	private final MetrologyRepository metrologyRepository;
	private final RunFeatureRepository featureRepository;
	private final ChangeRecordRepository changeRepository;
	private final PackStore pack;
	private final PasswordEncoder passwordEncoder;

	void users() {
		String hash = passwordEncoder.encode(PASSWORD);
		userRepository.save(new UserAccount(CVD_ENGINEER, "cvd.kim", hash, "김증착", Role.ENGINEER, List.of("CVD"), "증착기술팀"));
		userRepository.save(new UserAccount(ETCH_ENGINEER, "etch.lee", hash, "이식각", Role.ENGINEER, List.of("ETCH"), "식각기술팀"));
		userRepository.save(new UserAccount(CMP_ENGINEER, "cmp.park", hash, "박연마", Role.ENGINEER, List.of("CMP"), "연마기술팀"));
		userRepository.save(new UserAccount(MANAGER, "manager.choi", hash, "최관리", Role.MANAGER, List.of("CVD", "ETCH", "CMP"),
			"공정기술그룹"));
		// 정비 요청(create_maintenance_request)을 받는 설비 정비 담당 — 세 공정 PM을 맡는다
		userRepository.save(new UserAccount(MAINTENANCE, "mnt.jung", hash, "정정비", Role.ENGINEER, List.of("CVD", "ETCH", "CMP"),
			"설비정비팀"));
	}

	/** run · 5위치 계측 · run 특징 · 정비 이력 */
	void processData() {
		Map<String, RouteRun> byRun = new LinkedHashMap<>();
		for (Map<String, Object> r : pack.list("data/process-runs.json")) {
			String proc = str(r, "process");
			String recipe = str(r, "recipeId");
			RouteRun run = RouteRun.builder().runId(str(r, "runId")).waferId(str(r, "waferId")).lotId(str(r, "lotId"))
				.phase(str(r, "phase")).sequenceNo(ProcessEvidenceService.PROCESS_ORDER.indexOf(proc) + 1).processCode(proc)
				.stepName(STEP.get(proc)).equipmentId(str(r, "equipmentId")).recipeId(recipe)
				.recipeVersion(recipe.substring(recipe.lastIndexOf('-') + 1)).productId(str(r, "productId"))
				.layerId(str(r, "layerId")).material(str(r, "material"))
				.startedAt(PackStore.instant(str(r, "runStart"))).endedAt(PackStore.instant(str(r, "runEnd")))
				.readySeconds(num(r, "readySeconds").intValue()).processSeconds(num(r, "processSeconds").intValue())
				.shutdownSeconds(num(r, "shutdownSeconds").intValue()).controlMode(str(r, "controlMode"))
				.inputMeanA(num(r, "inputMeanA").doubleValue()).outputMeanA(num(r, "outputMeanA").doubleValue())
				.addedOrRemovedA(num(r, "addedOrRemovedA").doubleValue()).changeSign(num(r, "changeSign").intValue())
				.metrologyId(str(r, "metrologyId")).upstreamRunId(str(r, "upstreamRunId"))
				.metrologyAvailableAt(PackStore.instant(str(r, "metrologyAvailableAt")))
				.oocR1(Boolean.TRUE.equals(r.get("oocR1"))).oos(Boolean.TRUE.equals(r.get("oos"))).build();
			byRun.put(run.getRunId(), run);
		}
		routeRepository.saveAll(byRun.values());

		List<Metrology> metrology = new ArrayList<>();
		for (Map<String, Object> m : pack.list("data/site-metrology.json")) {
			RouteRun run = byRun.get(str(m, "runId"));
			String proc = str(m, "process");
			metrology.add(Metrology.builder().measurementId(str(m, "metrologyId") + "-" + str(m, "siteId"))
				.phase(str(m, "phase")).stage(ProcessEvidenceService.STAGE_OF.get(proc)).lotId(run.getLotId())
				.waferId(str(m, "waferId")).runId(str(m, "runId")).layerId(run.getLayerId()).featureId("film_thickness")
				.siteId(str(m, "siteId")).specId("QL-" + proc + "-V1").value(num(m, "filmThicknessA").doubleValue())
				.unit(str(m, "unit")).toolId(str(m, "method")).measuredAt(PackStore.instant(str(m, "measuredAt")))
				.availableAt(run.getMetrologyAvailableAt()).build());
		}
		metrologyRepository.saveAll(metrology);

		List<RunFeature> features = new ArrayList<>();
		for (Map<String, Object> f : pack.list("data/run-sensor-features.json")) {
			features.add(RunFeature.builder().runId(str(f, "runId")).waferId(str(f, "waferId")).lotId(str(f, "lotId"))
				.phase(str(f, "phase")).processCode(str(f, "process")).equipmentId(str(f, "equipmentId"))
				.sensorId(str(f, "sensorId")).unit(str(f, "unit")).recipeStep(str(f, "recipeStep"))
				.mean(num(f, "mean").doubleValue()).std(num(f, "std").doubleValue()).min(num(f, "min").doubleValue())
				.max(num(f, "max").doubleValue()).slopePerSecond(num(f, "slopePerSecond").doubleValue())
				.setpoint(f.get("setpoint") == null ? null : num(f, "setpoint").doubleValue()).build());
		}
		featureRepository.saveAll(features);

		List<ChangeRecord> changes = new ArrayList<>();
		for (Map<String, Object> c : pack.list("data/change-events.json")) {
			String eq = str(c, "equipmentId");
			changes.add(ChangeRecord.builder().changeId(str(c, "changeId")).processCode(eq.substring(0, eq.indexOf('-')))
				.equipmentId(eq).changeType(str(c, "type")).summary(str(c, "description"))
				.occurredAt(PackStore.instant(str(c, "occurredAt"))).recordedAt(PackStore.instant(str(c, "availableAt")))
				.recordedBy("PACK").rootCauseConfirmed(Boolean.TRUE.equals(c.get("rootCauseConfirmed")))
				.evidenceIds(new ArrayList<>(List.of("ev:change:" + str(c, "changeId")))).build());
		}
		changeRepository.saveAll(changes);
	}

	/** 현재 로트에서 R1(관리한계)을 벗어난 run — 이상 탐지 서비스가 사건으로 올리는 대상 */
	List<RouteRun> currentOutOfControl() {
		return routeRepository.findByPhase("CURRENT").stream().filter(RouteRun::isOocR1)
			.sorted((a, b) -> a.getRunId().compareTo(b.getRunId())).toList();
	}

	/**
	 * 지식 코퍼스 → 화면 P12 문서 (Agent의 지식 MCP도 같은 파일을 색인한다)
	 * <ul>
	 * <li>실제 원문 7편 — 문서 하나에 원문 PDF 쪽별 조각을 단락으로 (source-chunks.jsonl)</li>
	 * <li>팀 시연 점검 문서 15개 — rag-chunks.jsonl의 TEAM_SYNTHETIC만. 공개 요약 9개는 실제 원문과 겹쳐 뺀다</li>
	 * <li>가상 과거 사례 7건 — synthetic-cases.jsonl</li>
	 * </ul>
	 */
	@SuppressWarnings("unchecked")
	void documents(Instant now) {
		List<KnowledgeDocument> docs = new ArrayList<>();
		Map<String, List<Map<String, Object>>> byDoc = new LinkedHashMap<>();
		pack.sourceChunks().forEach(c -> byDoc.computeIfAbsent(str(c, "documentId"), k -> new ArrayList<>()).add(c));
		byDoc.forEach((docId, chunks) -> {
			Map<String, Object> first = chunks.getFirst();
			List<String> procs = chunks.stream().flatMap(c -> ((List<String>) c.getOrDefault("process", List.of())).stream())
				.filter(p -> List.of("CVD", "ETCH", "CMP").contains(p)).distinct().toList();
			List<Map<String, Object>> sections = new ArrayList<>();
			for (Map<String, Object> c : chunks) {
				sections.add(sec(str(c, "chunkId"), "원문 " + c.get("originalPdfPage") + "쪽 · " + c.get("retrievalTier"),
					str(c, "text")));
			}
			docs.add(KnowledgeDocument.builder().docId(docId).version(str(first, "version")).latest(true)
				.title("[실제 원문] " + str(first, "title")).docType("SOURCE_DOCUMENT")
				.processCode(procs.size() == 1 ? procs.getFirst() : "COMMON").sourceUrl(str(first, "sourceUrl"))
				.synthetic(false).approvedProcedure(false)
				.summary(str(first, "publisher") + " — 적용 한계: " + abbreviate(str(first, "applicabilityLimits"), 240))
				.sections(sections).publishedAt(now.minus(Duration.ofDays(1))).build());
		});
		Map<String, Map<String, Object>> causes = new LinkedHashMap<>();
		pack.causes().forEach(c -> causes.put(str(c, "causeId"), c));
		for (Map<String, Object> c : pack.lines("knowledge/rag-chunks.jsonl")) {
			if (!"TEAM_SYNTHETIC".equals(c.get("origin"))) {
				continue; // 공개 요약은 실제 원문(source-chunks)과 겹친다
			}
			String id = str(c, "chunkId");
			Map<String, Object> cause = id.startsWith("DEMO-") ? causes.get(id.substring(5)) : null;
			String proc = c.get("process") != null ? str(c, "process") : cause != null ? str(cause, "process") : "COMMON";
			docs.add(KnowledgeDocument.builder().docId(id).version(str(c, "version")).latest(true)
				.title("[점검 문서 · 시연] " + (cause != null ? cause.get("process") + " · " + cause.get("candidate") : id))
				.docType(str(c, "documentType")).processCode("ALL".equals(proc) ? "COMMON" : proc).synthetic(true)
				.approvedProcedure(Boolean.TRUE.equals(c.get("approvedForProductionAction")))
				.summary(abbreviate(str(c, "text"), 300)).sections(new ArrayList<>(List.of(sec("s1", "본문", str(c, "text")))))
				.publishedAt(now.minus(Duration.ofDays(30))).build());
		}
		for (Map<String, Object> k : pack.lines("knowledge/synthetic-cases.jsonl")) {
			List<Map<String, Object>> sections = new ArrayList<>();
			sections.add(sec("s1", "증상", str(k, "symptom")));
			sections.add(sec("s2", "근거", String.join(" / ", (List<String>) k.getOrDefault("evidence", List.of()))));
			sections.add(sec("s3", "대안 확인", String.join(" / ", (List<String>) k.getOrDefault("alternativeChecks", List.of()))));
			sections.add(sec("s4", "조치 (가상)", str(k, "action")));
			sections.add(sec("s5", "결과 (가상)", str(k, "outcome") + " — " + str(k, "resolutionStatus")));
			sections.add(sec("s6", "남은 불확실성", str(k, "remainingUncertainty")));
			docs.add(KnowledgeDocument.builder().docId(str(k, "caseId")).version(str(k, "version")).latest(true)
				.title("[가상 사례] " + str(k, "title")).docType("SYNTHETIC_CASE")
				.processCode("ALL".equals(str(k, "process")) ? "COMMON" : str(k, "process")).synthetic(true)
				.approvedProcedure(false).summary(str(k, "symptom") + " · " + str(k, "resolutionStatus")).sections(sections)
				.publishedAt(PackStore.instant(str(k, "availableAt"))).build());
		}
		docs.add(KnowledgeDocument.builder().docId("DOC-COMMON-001").version("v1").latest(true)
			.title("이상 사건 조사 보고서 작성 가이드").docType("REFERENCE").processCode("COMMON").synthetic(true)
			.approvedProcedure(false).summary("관측 사실 · 가설 · 조치 · 효과를 구분해 기록하는 방법.")
			.sections(new ArrayList<>(List.of(
				sec("s1", "구분", "관측 사실과 가설을 분리하고, 가설에는 지지 · 반대 근거를 함께 적는다."),
				sec("s2", "미해결 항목", "확인되지 않은 내용은 미해결 항목으로 남기며 원인을 단정하지 않는다."))))
			.publishedAt(now.minus(Duration.ofDays(120))).build());
		documentRepository.saveAll(docs);
	}

	private static Map<String, Object> sec(String id, String heading, String text) {
		Map<String, Object> m = new LinkedHashMap<>();
		m.put("sectionId", id);
		m.put("heading", heading);
		m.put("text", text);
		return m;
	}

	private static String abbreviate(String s, int max) {
		return s == null || s.length() <= max ? s : s.substring(0, max) + "…";
	}

	static String str(Map<String, Object> m, String key) {
		Object v = m.get(key);
		return v == null ? null : v.toString();
	}

	static Number num(Map<String, Object> m, String key) {
		Object v = m.get(key);
		return v == null ? 0 : (Number) v;
	}
}
