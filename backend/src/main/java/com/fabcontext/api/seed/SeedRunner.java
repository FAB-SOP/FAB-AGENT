package com.fabcontext.api.seed;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.fabcontext.api.auth.AuthUser;
import com.fabcontext.api.auth.UserAccountRepository;
import com.fabcontext.api.common.Ids;
import com.fabcontext.api.common.Ref;
import com.fabcontext.api.config.MutableClock;
import com.fabcontext.api.domain.action.ActionDtos.ActionInput;
import com.fabcontext.api.domain.action.ActionDtos.VerificationInput;
import com.fabcontext.api.domain.action.ActionService;
import com.fabcontext.api.domain.action.VerificationStatus;
import com.fabcontext.api.domain.analysis.AnalysisResult;
import com.fabcontext.api.domain.analysis.AnalysisResultRepository;
import com.fabcontext.api.domain.approval.ApprovalActionType;
import com.fabcontext.api.domain.approval.ApprovalDtos.Proposal;
import com.fabcontext.api.domain.approval.ApprovalService;
import com.fabcontext.api.domain.incident.Incident;
import com.fabcontext.api.domain.incident.IncidentRepository;
import com.fabcontext.api.domain.incident.IncidentStatus;
import com.fabcontext.api.domain.job.Job;
import com.fabcontext.api.domain.job.JobKind;
import com.fabcontext.api.domain.job.JobRepository;
import com.fabcontext.api.domain.job.JobService;
import com.fabcontext.api.domain.processdata.DataVersion;
import com.fabcontext.api.domain.processdata.PackStore;
import com.fabcontext.api.domain.processdata.ProcessEvidenceService;
import com.fabcontext.api.domain.processdata.RouteRun;
import com.fabcontext.api.domain.report.CauseClassificationInput;
import com.fabcontext.api.domain.report.Report;
import com.fabcontext.api.domain.report.ReportDtos.ReportConfirm;
import com.fabcontext.api.domain.report.ReportService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 시연 시나리오 시드. 과거 이력은 MutableClock을 잠시 과거로 옮겨 실제 서비스 로직으로 만든다.
 * 데이터 팩의 현재 로트 가운데 R1을 벗어난 run(EV-1006 · EV-1007 · EV-1009)이 사건이 되고, 세 사건 모두 기본 분석을 대기열에 둔다(Agent가 처리).
 * 과거 사건(EV-1018~1023)은 보고서 시연용 합성 조사 서술이다. LOT·설비는 화면상 맥락이며 실제 wafer/run과 연결하지 않는다.
 * 실제 Agent 분석이나 4M 분류를 수행하지 않고, 공정 원본·RAG·현재 이상 사건 생성 경로는 바꾸지 않는다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "fab.seed.enabled", havingValue = "true", matchIfMissing = true)
public class SeedRunner implements ApplicationRunner {

	private final SeedCatalog catalog;
	private final MutableClock clock;
	private final TransactionTemplate tx;
	private final UserAccountRepository userRepository;
	private final IncidentRepository incidentRepository;
	private final JobRepository jobRepository;
	private final JobService jobService;
	private final AnalysisResultRepository resultRepository;
	private final ActionService actionService;
	private final ReportService reportService;
	private final ApprovalService approvalService;
	private final DataVersion dataVersion;
	private final PackStore pack;

	@Override
	public void run(ApplicationArguments args) {
		if (userRepository.count() > 0) {
			// 기존 DB는 보존한다. 초기 서술 변경을 적용하려면 별도의 대상 한정 갱신 절차를 사용한다.
			log.info("기존 데이터가 있어 시드를 건너뜁니다.");
			return;
		}
		Instant now = Instant.now().truncatedTo(ChronoUnit.MINUTES);
		try {
			tx.executeWithoutResult(status -> seed(now));
		}
		finally {
			clock.reset();
		}
		log.info("시드 완료: 팩 {} · 사용자 cvd.kim / etch.lee / cmp.park / manager.choi / mnt.jung (비밀번호 {})", pack.dir(),
			SeedCatalog.PASSWORD);
	}

	private void seed(Instant now) {
		catalog.users();
		catalog.documents(now);
		catalog.processData();
		AuthUser cvd = user(SeedCatalog.CVD_ENGINEER);
		AuthUser cmp = user(SeedCatalog.CMP_ENGINEER);
		AuthUser manager = user(SeedCatalog.MANAGER);
		// ---- 과거 합성 조사 사례 — 표시 LOT만 맞추며 센서·계측 연결이나 4M 분류를 만들지 않는다.
		seedClosedHistory(now, manager);
		seedOpenHistory(now, cvd, cmp, manager);
		seedCurrentIncidents();
	}

	/** 종료된 과거 사건 EV-1018 · EV-1019 · EV-1020 */
	private void seedClosedHistory(Instant now, AuthUser manager) {
		Instant d18 = now.minus(Duration.ofDays(12));
		Incident ev1018 = incident(1018, "PAD_TEMP_DRIFT", "LOW", "CMP", d18);
		close(ev1018, manager, d18.plus(Duration.ofHours(20)), "합성 조사 사례의 확인 절차·인수인계 검토 내용 정리 완료");

		Instant d19 = now.minus(Duration.ofDays(6));
		Incident ev1019 = incident(1019, "DEP_RATE_DRIFT", "MEDIUM", "CVD", d19);
		close(ev1019, manager, d19.plus(Duration.ofHours(30)), "합성 조사 사례의 원료 배치·사용 조건 검토 내용 정리 완료");

		Instant d20 = now.minus(Duration.ofDays(5));
		Incident ev1020 = incident(1020, "PRESSURE_FLUCTUATION", "MEDIUM", "CMP", d20);
		close(ev1020, manager, d20.plus(Duration.ofHours(10)), "합성 조사 사례의 작업 방법·적용 절차 검토 내용 정리 완료");
	}

	/** 보고서까지 끝난 EV-1021 · 보류된 EV-1022 · 확인 요청 제안이 승인 대기인 EV-1023 */
	private void seedOpenHistory(Instant now, AuthUser cvd, AuthUser cmp, AuthUser manager) {
		Instant d21 = now.minus(Duration.ofDays(3));
		Incident ev1021 = incident(1021, "SLURRY_FLOW_DROP", "HIGH", "CMP", d21);
		resolvedWithReport(ev1021, cmp, d21);

		Instant d22 = now.minus(Duration.ofDays(2));
		Incident ev1022 = incident(1022, "PRESSURE_FLUCTUATION", "LOW", "CMP", d22);
		restoreLegacyStatus(ev1022, manager, d22.plus(Duration.ofHours(6)), IncidentStatus.ON_HOLD,
			"압력 편차의 원인을 구분할 센서 교정 이력·공급 및 제어 계통 점검·독립 측정 자료가 부족하여 추가 확인 대기");

		Instant d23 = now.minus(Duration.ofDays(1));
		Incident ev1023 = incident(1023, "DEP_RATE_DRIFT", "MEDIUM", "CVD", d23);
		at(d23.plus(Duration.ofHours(2)));
		Map<String, Object> payload = new LinkedHashMap<>();
		payload.put("ownerUserId", SeedCatalog.CMP_ENGINEER);
		payload.put("question", "EV-1023 로트의 CMP 후 두께에 이상이 있었는지 확인 부탁드립니다.");
		payload.put("targetProcessCode", "CMP");
		approvalService.createProposal(ev1023.getIncidentId(), cvd, new Proposal(ApprovalActionType.CHECK_REQUEST,
			payload, "합성 조사 사례의 증착 결과와 후속 CMP 확인 내용을 구분해 정리할 필요", List.of(), null, ev1023.getVersion()));
	}

	/** 팩의 현재 로트에서 R1(관리한계)을 벗어난 run = 이상 탐지 서비스가 올린 사건. EV-10NN = CURRENT-L0NN */
	private void seedCurrentIncidents() {
		for (RouteRun run : catalog.currentOutOfControl()) {
			int seq = 1000 + Integer.parseInt(run.getLotId().substring(run.getLotId().length() - 3));
			Incident incident = fromRun(seq, run);
			// 기본 분석을 하지 않은 채 대기열에 둔다 — 서버를 띄우면 Agent(외부 Worker 또는 시뮬레이터 대역)가 바로 처리한다.
			// 판정은 Agent 한 곳에서만 한다(시드가 결과를 미리 만들지 않는다)
			at(run.getMetrologyAvailableAt().plusSeconds(60));
			jobService.enqueue(incident, JobKind.BASIC, "svc_detector", null, null, Map.of(), null, null);
		}
	}

	/** R1 이탈 run 하나 → 사건. 이상 탐지 서비스는 판정만 넘기고 원인은 Agent가 조사한다 */
	private Incident fromRun(int seq, RouteRun r) {
		Map<String, Object> limit = pack.limit(r.getProcessCode()).orElseThrow();
		double ucl = ((Number) limit.get("ucl")).doubleValue();
		double lcl = ((Number) limit.get("lcl")).doubleValue();
		String side = r.getOutputMeanA() > ucl ? "UCL " + fmt(ucl) + " Å 초과" : "LCL " + fmt(lcl) + " Å 미만";
		String spec = limit.get("usl") == null ? "규격 미등록"
				: "규격 " + fmt(((Number) limit.get("lsl")).doubleValue()) + "–"
						+ fmt(((Number) limit.get("usl")).doubleValue()) + " Å " + (r.isOos() ? "밖 (OOS)" : "안 (OOC, OOS 아님)");
		String summary = r.getWaferId() + " " + r.getProcessCode() + " 후 5위치 평균 " + fmt(r.getOutputMeanA()) + " Å — " + side
				+ " (" + limit.get("limitId") + " · R1) · " + spec;
		Map<String, Object> metrics = Map.of("waferMeanA", ProcessEvidenceService.round(r.getOutputMeanA(), 1), "limitId",
			limit.get("limitId"), "ucl", ProcessEvidenceService.round(ucl, 1), "lcl", ProcessEvidenceService.round(lcl, 1),
			"rule", "R1_ONLY", "oos", r.isOos());
		return create(seq, "THICKNESS_OOC_R1", r.isOos() ? "HIGH" : "MEDIUM", r.getProcessCode(), r.getEquipmentId(),
			r.getLotId(), List.of(r.getWaferId()), List.of(r.getRunId()),
			pack.sensors().stream().filter(x -> r.getProcessCode().equals(x.get("process")))
				.map(x -> String.valueOf(x.get("sensorId"))).toList(),
			metrics, r.getMetrologyAvailableAt(), summary,
			r.getEquipmentId() + " " + r.getProcessCode() + " 후 두께 관리한계 이탈");
	}

	private static String fmt(double v) {
		return String.format(java.util.Locale.ROOT, "%,.1f", v);
	}

	private void resolvedWithReport(Incident incident, AuthUser engineer, Instant d) {
		at(d.plus(Duration.ofHours(2)));
		var action = actionService.record(incident.getIncidentId(), engineer, new ActionInput("PART_CHECK",
			"슬러리 공급 라인 점검 및 필터 교체", d.plus(Duration.ofMinutes(90)), List.of(), true, incident.getVersion()));
		at(d.plus(Duration.ofHours(20)));
		actionService.verify(action.actionId(), engineer, new VerificationInput(VerificationStatus.IMPROVED,
			"후속 12 run의 슬러리 유량이 기준 수준으로 회복", List.of(), true, action.version()));
		at(d.plus(Duration.ofHours(21)));
		Job reportJob = jobRepository.save(Job.builder().jobId(Ids.next("job")).incidentId(incident.getIncidentId())
			.kind(JobKind.REPORT).attempt(1).threadId(Ids.next("thr")).requestedBy(engineer.userId())
			.createdAt(clock.instant()).build());
		Report report = reportService.createDraft(incident, reportJob);
		jobService.succeed(reportJob, incident, new Ref("REPORT", report.getReportId()));
		at(d.plus(Duration.ofHours(22)));
		// 원인 분류는 기록된 조치(슬러리 공급 라인 점검 · 필터 교체)에 맞춘다
		reportService.confirm(report.getReportId(), engineer, new ReportConfirm(report.getContentRevision(), true,
			new CauseClassificationInput("MACHINE", List.of("slurry_flow"))));
		// 확정이 사건 종료까지 한다 — 따로 닫지 않는다
	}

	private Incident incident(int seq, String type, String severity, String process, Instant detected) {
		HistoricalStory story = historicalStory(seq);
		Incident incident = create(seq, type, severity, process, story.equipment(), story.lot(), List.of(), List.of(),
			List.of(), Map.of("demoNarrativePreview", true, "displayContextOnly", true,
				"originalDemoLot", "HIST-L%03d".formatted(seq - 1000)), detected,
			"[합성 조사 시연] " + story.overview() + " LOT 번호는 화면상 맥락이며 실제 센서·계측 근거 연결은 없습니다.", story.title());
		basic(incident, detected.plus(Duration.ofMinutes(4)), story);
		return incident;
	}

	private Incident create(int seq, String type, String severity, String process, String equipment, String lot,
			List<String> wafers, List<String> runs, List<String> sensors, Map<String, Object> metrics, Instant detected,
			String summary, String title) {
		at(detected.plusSeconds(30));
		Incident incident = Incident.builder()
			.incidentId(Ids.next("inc"))
			.displaySeq(seq)
			.title(title)
			.anomalyType(type)
			.severity(severity)
			.processCode(process)
			.equipmentId(equipment)
			.lotId(lot)
			.waferIds(wafers)
			.runIds(runs)
			.sensorIds(sensors)
			.metrics(metrics)
			.summary(summary)
			.sourceEventId("EVT-DET-" + seq)
			.source(wafers.isEmpty() ? "sensor-simulator" : "spc-r1-detector")
			.detectedAt(detected)
			.createdAt(clock.instant())
			.build();
		incident.record(clock.instant(), "DETECTED", "svc_detector", "이상 이벤트 접수 (" + type + ")", "EVENT",
			"EVT-DET-" + seq);
		return incidentRepository.save(incident);
	}

	private void basic(Incident incident, Instant at, HistoricalStory story) {
		at(at.minus(Duration.ofMinutes(3)));
		Job job = jobRepository.save(Job.builder().jobId(Ids.next("job")).incidentId(incident.getIncidentId())
			.kind(JobKind.BASIC).attempt(1).threadId(Ids.next("thr")).requestedBy("svc_simulator")
			.createdAt(clock.instant()).build());
		incident.setActiveJobId(job.getJobId());
		at(at);
		AnalysisResult result = resultRepository.save(historicalAnalysis(incident, job, story));
		jobService.succeed(job, incident, new Ref("ANALYSIS_RESULT", result.getResultId()));
		incident.setStatus(IncidentStatus.INVESTIGATING);
		incident.record(clock.instant(), "ANALYSIS_COMPLETED", "svc_simulator", "합성 조사 서술 등록: " + result.getSummary(),
			"ANALYSIS_RESULT", result.getResultId());
	}

	/** 과거 시연 서술을 저장한다. 공정 수치·RAG 검색 결과나 실제 Agent 판정으로 가장하지 않는다. */
	private AnalysisResult historicalAnalysis(Incident incident, Job job, HistoricalStory story) {
		String ref = "DEMO-STORY-" + incident.getDisplayId();
		List<Map<String, Object>> facts = new ArrayList<>();
		for (String statement : story.facts()) {
			Map<String, Object> fact = new LinkedHashMap<>();
			String label = "[합성 시연용 조사 서술] " + statement;
			fact.put("factId", "demo-" + (facts.size() + 1));
			fact.put("statement", label);
			fact.put("label", label);
			fact.put("value", null);
			fact.put("unit", null);
			fact.put("evidenceId", ref);
			fact.put("evidenceIds", List.of(ref));
			facts.add(fact);
		}
		Map<String, Object> cause = new LinkedHashMap<>();
		cause.put("causeId", "demo-story-" + incident.getDisplayId());
		cause.put("rank", 1);
		cause.put("priority", 1); // 화면용 원인 후보 순위. 조사 우선순위나 4M 분류가 아니다.
		cause.put("cause", story.hypothesis());
		cause.put("hypothesis", story.hypothesis());
		cause.put("processCode", incident.getProcessCode());
		cause.put("relation", "UNKNOWN");
		cause.put("status", "INSUFFICIENT_EVIDENCE");
		cause.put("supportingEvidence", List.of(ref));
		cause.put("supportingEvidenceIds", List.of(ref));
		cause.put("counterEvidence", List.of());
		cause.put("counterEvidenceIds", List.of());
		cause.put("check", String.join(" / ", story.checks()));
		cause.put("nextChecks", story.checks());
		cause.put("action", story.action());
		cause.put("verify", "추가 원본 자료와 담당자 검토로 확인합니다. 합성 서술만으로 개선·원인을 확정하지 않습니다.");
		cause.put("missingData", story.missing());
		cause.put("sourceIds", List.of());
		cause.put("matrixCauseIds", List.of());
		List<String> limitations = new ArrayList<>(List.of(
			"이 내용은 보고서 초안·조사 화면 확인을 위해 작성한 합성 서술이며 실제 Agent 분석 결과가 아닙니다.",
			"LOT·설비 표기는 시연 맥락입니다. 실제 wafer/run 연결과 센서·계측 원본은 변경하지 않았습니다.",
			"과거 조치·종료 메모·보고서도 합성 시연 이력이며, 새 서술을 입증하는 측정 자료가 아닙니다."));
		limitations.addAll(story.missing());
		return AnalysisResult.builder().resultId(Ids.next("res")).incidentId(incident.getIncidentId())
			.jobId(job.getJobId()).threadId(job.getThreadId()).kind("BASIC")
			.summary("과거 조사 요약 — 합성 시연용 서술. " + story.overview() + " 실제 공정 데이터의 분석 결과나 원인 확정이 아닙니다.")
			.observedFacts(facts).candidateCauses(new ArrayList<>(List.of(cause))).limitations(limitations)
			.evidenceRefs(new ArrayList<>(List.of(Map.of("evidenceId", ref, "type", "SYNTHETIC_NARRATIVE",
				"label", "합성 조사 서술 · 실측 근거 아님", "ref", "/incidents/" + incident.getIncidentId() + "?tab=analysis"))))
			.nextActions(new ArrayList<>()).dataVersion(dataVersion.current()).createdAt(clock.instant()).build();
	}

	private record HistoricalStory(String lot, String equipment, String title, String overview, List<String> facts,
			String hypothesis, List<String> checks, String action, List<String> missing) {
	}

	/** 6개 사례의 서술은 이 파일에만 두므로 초기 시드를 공유할 때 별도 데이터 파일이 필요 없다. */
	private static HistoricalStory historicalStory(int seq) {
		return switch (seq) {
			case 1018 -> new HistoricalStory(
				"CURRENT-L004",
				"CMP-PM2",
				"CMP-PM2 정비 후 확인 절차 누락 조사",
				"정비 후 확인 체크리스트의 필수 항목이 누락된 상황을 가정한 작업 이행 조사 사례입니다.",
				List.of("가상 인수인계 기록에서는 정비 후 확인 항목 일부가 미작성된 상태로 다음 업무에 전달되었습니다.", "가상 검토에서는 적용 절차에 필수 확인 항목이 있었지만 작업자가 완료 여부를 남기지 않은 상황으로 구성했습니다.", "후속 확인과 체크리스트 재작성 내용을 보고서에 정리하는 흐름이며, L004 실제 공정값의 이상을 주장하지 않습니다."),
				"작업 확인·인수인계 이행 누락을 중심으로 정리한 조사 사례",
				List.of("적용 체크리스트와 완료 기록 대조", "작업 확인·인수인계 단계의 누락 범위 정리"),
				"보고서 초안에 확인 누락, 재확인 절차, 재발 방지 확인 항목을 구분해 작성합니다.",
				List.of("실제 체크리스트·작업자 확인 원본은 생성하지 않았습니다."));
			case 1019 -> new HistoricalStory(
				"CURRENT-L005",
				"CVD-PM1",
				"CVD-PM1 원료 배치 변경 관련 조사",
				"동일한 작업 조건에서 원료 배치가 바뀐 뒤 결과 차이를 확인하는 재료 관련 조사 사례입니다.",
				List.of("가상 조사 서술에서는 원료 공급 배치 교체와 공정 결과 차이가 함께 검토된 상황으로 구성했습니다.", "가상 비교에서는 설비 설정과 작업 절차의 변경이 없고 원료 배치·보관·사용 조건을 우선 확인하는 흐름입니다.", "실제 L005의 두께 이상이나 가스 품질 불량을 입증한 내용이 아니며, 배치 비교와 독립 확인 필요성을 보고서에 남깁니다."),
				"원료 배치·보관·사용 조건을 중심으로 정리한 조사 사례",
				List.of("원료 배치 전환 시각과 사용 범위 확인", "재료 성적서·보관·유효기간과 비교 결과 확인"),
				"원료 변경 맥락, 설비·방법과 구분한 확인 내용, 재료 비교의 한계를 초안에 작성합니다.",
				List.of("실제 배치 이력·성적서·비교 측정 원본은 생성하지 않았습니다."));
			case 1020 -> new HistoricalStory(
				"CURRENT-L002",
				"CMP-PM1",
				"CMP-PM1 적용 작업 방법 변경 조사",
				"작업 지침 개정 이후 적용 방법이 달라진 상황을 가정한 절차·방법 관련 조사 사례입니다.",
				List.of("가상 조사에서는 적용 작업 지침 버전이 바뀌면서 확인 순서와 수행 방법이 달라진 상황으로 구성했습니다.", "가상 비교는 작업자의 지침 준수 여부와 별개로 작업 방법 자체의 적정성·변경 승인·적용 시점을 확인하는 흐름입니다.", "실제 L002의 recipeId·처리시간·설정값은 바꾸지 않았으며, 레시피 변경이나 두께 이상을 실측으로 확인했다는 뜻이 아닙니다."),
				"작업 방법·절차 개정과 적용 기준을 중심으로 정리한 조사 사례",
				List.of("개정 전후 작업 지침과 적용 시점 비교", "방법 변경의 검토·승인 범위와 재확인 계획 정리"),
				"방법 변경 내용과 검토 사항을 초안에 정리하되 실제 Parameter 변경·수행으로 서술하지 않습니다.",
				List.of("실제 개정 지침·승인서·방법 비교 실험 원본은 생성하지 않았습니다."));
			case 1021 -> new HistoricalStory(
				"CURRENT-L007",
				"CMP-PM3",
				"CMP-PM3 슬러리 공급 계통 점검 조사",
				"슬러리 공급 계통의 점검·조치 내용을 보고서로 정리하기 위한 설비 관련 과거 조사 사례입니다.",
				List.of("가상 점검 서술에서는 슬러리 공급 라인과 필터의 유량 저하 요인을 점검한 상황으로 구성했습니다.", "가상 정리에서는 재료 배치와 작업 방법의 변화보다 공급 계통의 상태 확인·정비 검토를 중심으로 다룹니다.", "화면상 L007 맥락을 사용하지만 이 사건에 실제 wafer/run을 새로 연결하거나 점검·복구 계측을 추가하지 않았습니다."),
				"슬러리 공급 라인·필터 상태를 중심으로 정리한 조사 사례",
				List.of("공급 라인·필터·유량계의 점검 범위 확인", "조치 수행 기록과 독립적인 후속 확인 자료 구분"),
				"설비 점검·조치 맥락을 초안에 정리하고, 기존 합성 조치 기록과 실제 측정 검증을 구분합니다.",
				List.of("새로운 점검 원본·후속 회복 측정은 생성하지 않았습니다. 기존 확정 보고서는 수정 전 내용을 보존합니다."));
			case 1022 -> new HistoricalStory(
				"CURRENT-L006",
				"CMP-PM3",
				"CMP-PM3 압력 편차 원인 추가 확인",
				"압력 편차의 원인을 구분할 추가 자료가 없어 결론을 보류하는 조사 사례입니다.",
				List.of("가상 서술에서는 압력 편차가 보고되었으나 센서 readback, 공급·제어 계통, 작업 조건 중 어디에서 비롯됐는지 확인되지 않았습니다.", "재료·방법·작업 이행과 설비 상태를 구분할 자료가 부족하므로 특정 원인으로 단정하지 않는 흐름입니다.", "화면상 L006 맥락이며 실제 압력 시계열을 이 사건에 연결하지 않았습니다. 추가 점검·재측정 필요사항을 보고서에 남깁니다."),
				"독립적인 점검·측정 근거 부족으로 원인 판단을 유보한 조사 사례",
				List.of("센서 교정·readback과 공급·제어 계통 확인", "추가 자료 확보 후 원인 재검토"),
				"미확인 원인과 필요한 추가 자료를 초안에 정리합니다. 임의 설정값 조정을 제안하지 않습니다.",
				List.of("원인을 구분할 교정·현장 점검·독립 측정 자료가 없습니다."));
			case 1023 -> new HistoricalStory(
				"CURRENT-L010",
				"CVD-PM2",
				"CVD-PM2 증착 결과 공정 간 추가 확인",
				"현재 CVD 담당 범위를 유지하면서 후속 공정 확인 요청과 미확인 사항을 정리하는 협업 조사 사례입니다.",
				List.of("가상 서술에서는 증착 결과 차이에 대해 후속 공정 담당자의 확인이 필요한 상황으로 구성했습니다.", "가상 조사에서는 설비·재료·방법·작업 이행을 구분할 회신 근거가 부족해 원인을 미확정으로 유지합니다.", "L010은 화면상 맥락이며 실제 L010의 증착 불량이나 후공정 영향을 확인한 내용은 아닙니다. 기존 요청 상태는 유지합니다."),
				"공정 간 확인 회신과 추가 근거를 기다리는 조사 사례",
				List.of("기존 공정 간 확인 요청의 회신 내용 검토", "입출고 결과와 변경 이력 확보 후 원인 재검토"),
				"요청 배경·미확인 사항·회신 후 확인 계획을 초안에 정리합니다.",
				List.of("새로운 회신·원인 확정·후속 공정 측정은 생성하지 않았습니다."));
			default -> throw new IllegalArgumentException("Unknown historical story: " + seq);
		};
	}

	private void close(Incident incident, AuthUser manager, Instant at, String reason) {
		restoreLegacyStatus(incident, manager, at, IncidentStatus.CLOSED, reason);
	}

	/**
	 * 보고서 확정 전 규칙(관리자 보류 · 종료) 시절의 이력 데이터 — 지금 규칙에는 이런 상태를 만드는 API가 없어 시드만 직접 넣는다.
	 * 이력 문구는 예전 관리자 전이와 같은 모양으로 남긴다.
	 */
	private void restoreLegacyStatus(Incident incident, AuthUser manager, Instant at, IncidentStatus status, String reason) {
		at(at);
		IncidentStatus from = incident.getStatus();
		incident.setStatus(status);
		incident.record(clock.instant(), "STATUS_CHANGED", manager.userId(), from + " → " + status + ": " + reason, "INCIDENT",
			incident.getIncidentId());
		incidentRepository.save(incident);
	}

	private AuthUser user(String userId) {
		return AuthUser.from(userRepository.findById(userId).orElseThrow()).withoutPassword();
	}

	private void at(Instant instant) {
		clock.travelTo(instant);
	}
}
