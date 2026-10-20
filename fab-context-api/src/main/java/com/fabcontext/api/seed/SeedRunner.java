package com.fabcontext.api.seed;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
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
import com.fabcontext.api.domain.incident.IncidentDtos.IncidentTransition;
import com.fabcontext.api.domain.incident.IncidentRepository;
import com.fabcontext.api.domain.incident.IncidentService;
import com.fabcontext.api.domain.incident.IncidentStatus;
import com.fabcontext.api.domain.job.Job;
import com.fabcontext.api.domain.job.JobKind;
import com.fabcontext.api.domain.job.JobRepository;
import com.fabcontext.api.domain.job.JobService;
import com.fabcontext.api.domain.processdata.DataVersion;
import com.fabcontext.api.domain.processdata.PackStore;
import com.fabcontext.api.domain.processdata.ProcessEvidenceService;
import com.fabcontext.api.domain.processdata.RouteRun;
import com.fabcontext.api.domain.report.Report;
import com.fabcontext.api.domain.report.ReportDtos.ReportConfirm;
import com.fabcontext.api.domain.report.ReportService;
import com.fabcontext.api.simulator.AnalysisFactory;
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
 * 과거 사건(EV-1018~1023)의 기본 분석은 시뮬레이터 대역 결과(「실제 분석 아님」)다.
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
	private final IncidentService incidentService;
	private final JobRepository jobRepository;
	private final JobService jobService;
	private final AnalysisFactory analysisFactory;
	private final AnalysisResultRepository resultRepository;
	private final ActionService actionService;
	private final ReportService reportService;
	private final ApprovalService approvalService;
	private final DataVersion dataVersion;
	private final PackStore pack;

	@Override
	public void run(ApplicationArguments args) {
		if (userRepository.count() > 0) {
			// Postgres처럼 데이터가 남는 DB에서는 처음 한 번만 시드한다. 다시 넣으려면 backend 스키마를 지운다.
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

		// ---- 과거 사건 (추이 · 대시보드용 — 팩 데이터와 연결된 웨이퍼 없음)
		Instant d18 = now.minus(Duration.ofDays(12));
		Incident ev1018 = incident(1018, "PAD_TEMP_DRIFT", "LOW", "CMP", "CMP-PM2", "HIST-L018", d18,
			"CMP-PM2 플래튼 모터 전류 상승 추세");
		close(ev1018, manager, d18.plus(Duration.ofHours(20)), "센서 교정 후 정상 범위 확인, 공정 영향 없음");

		Instant d19 = now.minus(Duration.ofDays(6));
		Incident ev1019 = incident(1019, "DEP_RATE_DRIFT", "MEDIUM", "CVD", "CVD-PM2", "HIST-L019", d19,
			"CVD 후 두께 추세 이탈 2매");
		close(ev1019, manager, d19.plus(Duration.ofHours(30)), "재측정 결과 계측 오류로 확인");

		Instant d20 = now.minus(Duration.ofDays(5));
		Incident ev1020 = incident(1020, "PRESSURE_FLUCTUATION", "MEDIUM", "CMP", "CMP-PM1", "HIST-L020", d20,
			"CMP-PM1 헤드 압력 변동");
		close(ev1020, manager, d20.plus(Duration.ofHours(10)), "단발성 변동, 후속 run 정상");

		Instant d21 = now.minus(Duration.ofDays(3));
		Incident ev1021 = incident(1021, "SLURRY_FLOW_DROP", "HIGH", "CMP", "CMP-PM2", "HIST-L021", d21,
			"CMP-PM2 슬러리 유량 저하");
		resolvedWithReport(ev1021, cmp, manager, d21);

		Instant d22 = now.minus(Duration.ofDays(2));
		Incident ev1022 = incident(1022, "PAD_TEMP_DRIFT", "LOW", "CMP", "CMP-PM2", "HIST-L022", d22,
			"CMP-PM2 패드 온도 드리프트");
		at(d22.plus(Duration.ofHours(6)));
		incidentService.transition(ev1022.getIncidentId(), manager, new IncidentTransition(IncidentStatus.ON_HOLD,
			"패드 온도 센서 교정 후 추가 데이터 수집 대기", List.of(), ev1022.getVersion()));

		Instant d23 = now.minus(Duration.ofDays(1));
		Incident ev1023 = incident(1023, "DEP_RATE_DRIFT", "MEDIUM", "CVD", "CVD-PM1", "HIST-L023", d23,
			"CVD-PM1 증착 속도 드리프트");
		at(d23.plus(Duration.ofHours(2)));
		Map<String, Object> payload = new LinkedHashMap<>();
		payload.put("ownerUserId", SeedCatalog.CMP_ENGINEER);
		payload.put("question", "EV-1023 로트의 CMP 후 두께에 이상이 있었는지 확인 부탁드립니다.");
		payload.put("targetProcessCode", "CMP");
		approvalService.createProposal(ev1023.getIncidentId(), cvd, new Proposal(ApprovalActionType.CHECK_REQUEST,
			payload, "증착 속도 드리프트가 후속 CMP 결과에 영향을 줬는지 확인 필요", List.of(), null, ev1023.getVersion()));

		// ---- 팩의 현재 로트에서 R1(관리한계)을 벗어난 run = 이상 탐지 서비스가 올린 사건. EV-10NN = CURRENT-L0NN
		for (RouteRun r : catalog.currentOutOfControl()) {
			int seq = 1000 + Integer.parseInt(r.getLotId().substring(r.getLotId().length() - 3));
			Incident inc = fromRun(seq, r);
			// 기본 분석을 하지 않은 채 대기열에 둔다 — 서버를 띄우면 Agent(외부 Worker 또는 시뮬레이터 대역)가 바로 처리한다.
			// 판정은 Agent 한 곳에서만 한다(시드가 결과를 미리 만들지 않는다)
			at(r.getMetrologyAvailableAt().plusSeconds(60));
			jobService.enqueue(inc, JobKind.BASIC, "svc_detector", null, null, Map.of(), null, null);
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

	private void resolvedWithReport(Incident incident, AuthUser engineer, AuthUser manager, Instant d) {
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
		reportService.confirm(report.getReportId(), engineer, new ReportConfirm(report.getContentRevision(), true));
		close(incident, manager, d.plus(Duration.ofHours(23)), "조치 효과 확인 및 보고서 v1 확정");
	}

	private Incident incident(int seq, String type, String severity, String process, String equipment, String lot,
			Instant detected, String title) {
		Incident incident = create(seq, type, severity, process, equipment, lot, List.of(), List.of(), List.of(), Map.of(),
			detected, title, title);
		basic(incident, detected.plus(Duration.ofMinutes(4)));
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

	private void basic(Incident incident, Instant at) {
		at(at.minus(Duration.ofMinutes(3)));
		Job job = jobRepository.save(Job.builder().jobId(Ids.next("job")).incidentId(incident.getIncidentId())
			.kind(JobKind.BASIC).attempt(1).threadId(Ids.next("thr")).requestedBy("svc_simulator")
			.createdAt(clock.instant()).build());
		incident.setActiveJobId(job.getJobId());
		at(at);
		AnalysisResult result = resultRepository.save(analysisFactory.basic(incident, job));
		jobService.succeed(job, incident, new Ref("ANALYSIS_RESULT", result.getResultId()));
		incident.setStatus(IncidentStatus.INVESTIGATING);
		incident.record(clock.instant(), "ANALYSIS_COMPLETED", "agent", "기본 분석 완료: " + result.getSummary(),
			"ANALYSIS_RESULT", result.getResultId());
	}

	private void close(Incident incident, AuthUser manager, Instant at, String reason) {
		at(at);
		incidentService.transition(incident.getIncidentId(), manager,
			new IncidentTransition(IncidentStatus.CLOSED, reason, List.of(), incident.getVersion()));
	}

	private AuthUser user(String userId) {
		return AuthUser.from(userRepository.findById(userId).orElseThrow()).withoutPassword();
	}

	private void at(Instant instant) {
		clock.travelTo(instant);
	}
}
