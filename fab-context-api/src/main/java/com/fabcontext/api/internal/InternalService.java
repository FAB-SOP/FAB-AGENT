package com.fabcontext.api.internal;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import com.fabcontext.api.auth.Role;
import com.fabcontext.api.auth.UserAccount;
import com.fabcontext.api.auth.UserAccountRepository;
import com.fabcontext.api.common.Json;
import com.fabcontext.api.common.Ref;
import com.fabcontext.api.domain.action.IncidentAction;
import com.fabcontext.api.domain.action.IncidentActionRepository;
import com.fabcontext.api.domain.approval.Approval;
import com.fabcontext.api.domain.approval.ApprovalActionType;
import com.fabcontext.api.domain.approval.ApprovalService;
import com.fabcontext.api.domain.checkrequest.CheckRequest;
import com.fabcontext.api.domain.checkrequest.CheckRequestRepositories.CheckRequestRepository;
import com.fabcontext.api.domain.checkrequest.CheckRequestRepositories.ReplyRepository;
import com.fabcontext.api.domain.checkrequest.Reply;
import com.fabcontext.api.domain.incident.Incident;
import com.fabcontext.api.domain.incident.IncidentRepository;
import com.fabcontext.api.domain.job.Job;
import com.fabcontext.api.domain.job.JobKind;
import com.fabcontext.api.domain.job.JobRepository;
import com.fabcontext.api.domain.job.JobService;
import com.fabcontext.api.domain.job.JobStatus;
import com.fabcontext.api.domain.processdata.ChangeRecord;
import com.fabcontext.api.domain.processdata.DataVersion;
import com.fabcontext.api.domain.processdata.PackStore;
import com.fabcontext.api.domain.processdata.PackStore.Trace;
import com.fabcontext.api.domain.processdata.ProcessDataRepositories.ChangeRecordRepository;
import com.fabcontext.api.domain.processdata.ProcessDataRepositories.RouteRunRepository;
import com.fabcontext.api.domain.processdata.ProcessEvidenceService;
import com.fabcontext.api.domain.processdata.SegmentDetector;
import com.fabcontext.api.domain.processdata.RouteRun;
import com.fabcontext.api.domain.report.Report;
import com.fabcontext.api.domain.report.ReportRepository;
import com.fabcontext.api.domain.report.ReportSections;
import com.fabcontext.api.domain.report.ReportService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 내부 API(/internal/**) — 업무 MCP와 Agent Worker만 부른다. 공개 API와 같은 공통 Service · Repository를 쓴다.
 * 데이터 도구 이름 · 조회 순서는 데이터 팩의 Agent 계약(fab-causal-pack-v1/knowledge/agent-contract.json)을 따른다.
 * 응답 data는 도구 결과 공통 형식 {data, evidenceRefs, dataVersion, warnings, synthetic}이다.
 * 분석 시점(팩의 analysisAsOf) 이후 공개된 계측 · run · 정비 기록은 돌려주지 않는다 — 서버가 강제한다.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class InternalService {

	static final int MAX_ITEMS = 20;

	static final int MAX_TRACE_POINTS = 120;

	static final ZoneId KST = ZoneId.of("Asia/Seoul");

	static final String MAINTENANCE_TEAM = "설비정비팀";

	/** 계약 actionType — 공개 API와 같은 이름이다 */
	/** 내부 API가 받는 actionType — 팀 계약 이름(CREATE_*)과 공개 이름 둘 다 받는다 */
	static final List<String> ACTION_TYPES = java.util.Arrays.stream(ApprovalActionType.values())
		.map(ApprovalActionType::internalName).toList();

	private final IncidentRepository incidentRepository;
	private final RouteRunRepository routeRepository;
	private final ChangeRecordRepository changeRepository;
	private final UserAccountRepository userRepository;
	private final CheckRequestRepository checkRequestRepository;
	private final ReplyRepository replyRepository;
	private final IncidentActionRepository actionRepository;
	private final ReportRepository reportRepository;
	private final ReportService reportService;
	private final ApprovalService approvalService;
	private final JobRepository jobRepository;
	private final JobService jobService;
	private final ProcessEvidenceService evidence;
	private final PackStore pack;
	private final DemoFaults faults;
	private final DataVersion dataVersion;

	// ------------------------------------------------------------------ 사건

	public Map<String, Object> incident(String id) {
		faults.maybeFail("get_incident");
		Incident i = resolve(id);
		return env(incidentView(i), List.of(ref("E-INC-" + i.getDisplayId(), "METROLOGY", i.getDisplayId())), List.of());
	}

	public Map<String, Object> incidents(String processCode, String status) {
		faults.maybeFail("list_incidents");
		List<Map<String, Object>> items = incidentRepository.findAll().stream()
			.filter(i -> processCode == null || processCode.equals(i.getProcessCode()))
			.filter(i -> status == null || "ALL".equals(status) || i.getStatus().name().equals(status))
			.sorted(Comparator.comparing(Incident::getDetectedAt).reversed()).limit(MAX_ITEMS)
			.map(this::incidentView).toList();
		return env(Map.of("items", items), List.of(), List.of());
	}

	// ------------------------------------------------------------------ 데이터 팩 도구 (agent-contract dataMcpTools)

	/** get_wafer_lineage — 웨이퍼의 실제 공정 경로. PM 이름이 같다고 앞뒤 장비를 연결하지 않는다 */
	public Map<String, Object> lineage(String waferId) {
		faults.maybeFail("get_wafer_lineage");
		List<RouteRun> runs = routeRepository.findByWaferIdOrderBySequenceNoAsc(waferId);
		if (runs.isEmpty()) {
			throw InternalError.notFound("웨이퍼 " + waferId + "가 없습니다.", "예: W-CURRENT-009-3 (로트 CURRENT-L009의 3번째 웨이퍼)");
		}
		List<Map<String, Object>> steps = runs.stream().map(r -> {
			Map<String, Object> m = new LinkedHashMap<>();
			m.put("sequenceNo", r.getSequenceNo());
			m.put("processCode", r.getProcessCode());
			m.put("equipmentId", r.getEquipmentId());
			m.put("runId", r.getRunId());
			m.put("upstreamRunId", r.getUpstreamRunId());
			m.put("recipeId", r.getRecipeId());
			m.put("startedAt", iso(r.getStartedAt()));
			m.put("endedAt", iso(r.getEndedAt()));
			m.put("metrologyAvailableAt", iso(r.getMetrologyAvailableAt()));
			return m;
		}).toList();
		Map<String, Object> data = new LinkedHashMap<>();
		data.put("waferId", waferId);
		data.put("lotId", runs.getFirst().getLotId());
		data.put("phase", runs.getFirst().getPhase());
		data.put("path", String.join(" → ", runs.stream().map(r -> r.getProcessCode() + ":" + r.getEquipmentId()).toList()));
		data.put("steps", steps);
		return env(data, List.of(ref("E-LIN-" + waferId, "ROUTE", waferId)), List.of());
	}

	/** get_run_and_metrology — run · 5위치 계측 · 평균 · 증착/제거량 · 관리한계 R1 · 정상 분포 대비 편차(서버 계산) */
	public Map<String, Object> run(String runId) {
		faults.maybeFail("get_run_and_metrology");
		RouteRun r = loadRun(runId);
		Map<String, Object> ev = evidence.runEvidence(r, asOf());
		List<String> warnings = new ArrayList<>();
		if (!Boolean.TRUE.equals(ev.get("available"))) {
			warnings.add(String.valueOf(ev.get("unavailableReason")));
		}
		return env(ev, List.of(ref("E-RUN-" + runId, "METROLOGY", runId)), warnings);
	}

	/** get_run_features — 센서 본 처리 평균 · 참고 범위 이탈 · 설정값 차이 · 같은 PM 정상 run 대비 편차 */
	public Map<String, Object> features(String runId, List<String> sensorIds) {
		faults.maybeFail("get_run_features");
		RouteRun r = loadRun(runId);
		requireRunEnded(r);
		List<Map<String, Object>> items = evidence.featureEvidence(r, sensorIds);
		if (sensorIds != null && items.isEmpty()) {
			throw InternalError.notFound("run " + runId + "에 센서 " + sensorIds + " 특징이 없습니다.", "가능한 센서: " + sensorList(r));
		}
		Map<String, Object> data = new LinkedHashMap<>();
		data.put("runId", runId);
		data.put("processCode", r.getProcessCode());
		data.put("equipmentId", r.getEquipmentId());
		data.put("scope", "RUN_PROCESS_STEADY_MEAN");
		data.put("items", items);
		data.put("outsideReferenceBand", items.stream().filter(f -> Boolean.TRUE.equals(f.get("outsideReferenceBand")))
			.map(f -> f.get("sensorId")).toList());
		return env(data, List.of(ref("E-FEAT-" + runId, "SENSOR", runId)),
			List.of("참고 범위는 BASELINE run 평균의 분포이며 원시 점 단위 SPC가 아닙니다. 센서 하나와 고장을 일대일로 대응시키지 않습니다."));
	}

	/** get_sensor_trace — 원시 시계열을 구간 평균으로 줄여 돌려준다(최대 MAX_TRACE_POINTS점) */
	public Map<String, Object> trace(String runId, String sensorId, String step, Integer maxPoints) {
		faults.maybeFail("get_sensor_trace");
		RouteRun r = loadRun(runId);
		requireRunEnded(r);
		Trace t = pack.trace(runId, sensorId).orElseThrow(() -> InternalError.notFound(
			"run " + runId + "의 " + sensorId + " 원시 시계열이 없습니다.",
			"BASELINE run은 원시 시계열 없이 특징만 있습니다(get_run_features). 가능한 센서: " + sensorList(r)));
		int n = Math.max(10, Math.min(MAX_TRACE_POINTS, maxPoints == null ? 60 : maxPoints));
		long readyMs = r.getReadySeconds() * 1000L;
		long processEndMs = readyMs + r.getProcessSeconds() * 1000L;
		List<double[]> picked = new ArrayList<>(); // [ms, value]
		for (int i = 0; i < t.values().length; i++) {
			long ms = i * t.intervalMs();
			String s = ms < readyMs ? "READY" : ms < processEndMs ? "PROCESS" : "END";
			if ((step == null || step.equalsIgnoreCase(s)) && !Double.isNaN(t.values()[i])) {
				picked.add(new double[] {ms, t.values()[i]});
			}
		}
		List<Map<String, Object>> points = new ArrayList<>();
		int bucket = Math.max(1, (int) Math.ceil(picked.size() / (double) n));
		for (int i = 0; i < picked.size(); i += bucket) {
			List<double[]> part = picked.subList(i, Math.min(picked.size(), i + bucket));
			double mean = part.stream().mapToDouble(p -> p[1]).average().orElse(Double.NaN);
			points.add(Map.of("tMs", (long) part.getFirst()[0], "mean", ProcessEvidenceService.round(mean, 4), "n", part.size()));
		}
		var stats = picked.stream().mapToDouble(p -> p[1]).summaryStatistics();
		Map<String, Object> data = new LinkedHashMap<>();
		data.put("runId", runId);
		data.put("sensorId", sensorId);
		data.put("unit", t.unit());
		data.put("t0", iso(t.t0()));
		data.put("sourceIntervalMs", t.intervalMs());
		data.put("step", step == null ? "ALL" : step.toUpperCase());
		data.put("phases", Map.of("readyMs", readyMs, "processEndMs", processEndMs, "note", "PROCESS 시작 후 2초는 초기 전이"));
		data.put("setpointProfile", t.setpointProfile());
		data.put("sampleCount", picked.size());
		data.put("summary", picked.isEmpty() ? Map.of() : Map.of("mean", ProcessEvidenceService.round(stats.getAverage(), 4),
			"min", stats.getMin(), "max", stats.getMax()));
		data.put("points", points);
		data.put("quality", t.quality());
		// 지속 편차 구간(DEMO-SEGMENT-V2) — 시계열 참조 범위(sensorEnvelope)로 찾은 관측. 고장 시점 · 원인 확정이 아니다
		Map<String, Object> envelope = pack.envelope(r.getProcessCode(), sensorId).orElse(null);
		List<Map<String, Object>> segments = SegmentDetector.detect(r, t, envelope);
		data.put("sensorEnvelope", envelope);
		data.put("segments", segments);
		data.put("segmentRule", Map.of("ruleVersion", SegmentDetector.RULE_VERSION, "timeBase", "run 시작(t0)부터 잰 초",
			"note", "PROCESS 처음 2초 · READY · END 제외, 2초 이동평균이 sensorEnvelope 밖으로 5초 이상 — 4σ · 2초 · 5초는 시연 선택값"));
		List<Map<String, Object>> refs = new ArrayList<>(List.of(ref("E-TRC-" + runId + "-" + sensorId, "SENSOR", runId + ":" + sensorId)));
		if (!segments.isEmpty()) {
			refs.add(ref("E-SEG-" + runId + "-" + sensorId, "SENSOR", runId + ":" + sensorId + ":segments"));
		}
		List<String> warnings = new ArrayList<>(List.of("구간 평균으로 줄인 값입니다. 원시 점은 독립된 정상 run이 아니며, 없는 고속 값을 보간하지 않았습니다."));
		if (envelope != null) {
			warnings.add("구간은 sensorEnvelope(center ± 4σ, 2초 이동평균, 5초 지속 — 팀 시연 선택값, calibratedForRealTool=false)로 찾은 관측입니다. "
					+ "고장 발생 시점이나 원인 확정이 아닙니다.");
		}
		return env(data, refs, warnings);
	}

	/** get_frozen_limits — 동결 관리한계(I, R1)와 센서 특징 참고 범위 */
	public Map<String, Object> limits(String processCode) {
		faults.maybeFail("get_frozen_limits");
		List<Map<String, Object>> limits = pack.limits().stream()
			.filter(l -> processCode == null || processCode.equals(l.get("process"))).toList();
		if (limits.isEmpty()) {
			throw InternalError.invalid("processCode는 CVD · ETCH · CMP 중 하나입니다.", "");
		}
		Map<String, Object> data = new LinkedHashMap<>();
		data.put("qualityLimits", limits);
		data.put("featureReferenceBands", pack.sensors().stream()
			.filter(s -> processCode == null || processCode.equals(s.get("process")))
			.map(s -> pack.band(String.valueOf(s.get("process")), String.valueOf(s.get("sensorId"))).orElse(null))
			.filter(Objects::nonNull).toList());
		data.put("sensorCatalog", pack.sensors().stream()
			.filter(s -> processCode == null || processCode.equals(s.get("process"))).toList());
		String key = processCode == null ? "ALL" : processCode;
		return env(data, List.of(ref("E-LIM-" + key, "METROLOGY", key)),
			List.of("관리한계는 BASELINE으로만 정했고 현재 · 후속 결과로 넓히지 않았습니다. 등록 규칙은 R1뿐이라 "
					+ "'어떤 규칙으로도 검출되지 않았다'고 말하지 않습니다. 센서 USL/LSL은 등록하지 않았습니다."));
	}

	/** get_change_events — 정비 · 설정 변경 이력 (분석 시점 이전에 기록된 것만) */
	public Map<String, Object> changes(String equipmentId) {
		faults.maybeFail("get_change_events");
		Instant asOf = asOf();
		List<Map<String, Object>> rows = changeRepository.findAll().stream()
			.filter(c -> equipmentId == null || equipmentId.equals(c.getEquipmentId()))
			.filter(c -> c.getRecordedAt() == null || !c.getRecordedAt().isAfter(asOf))
			.sorted(Comparator.comparing(ChangeRecord::getOccurredAt).reversed())
			.map(c -> {
				Map<String, Object> m = new LinkedHashMap<>();
				m.put("changeId", c.getChangeId());
				m.put("processCode", c.getProcessCode());
				m.put("equipmentId", c.getEquipmentId());
				m.put("type", c.getChangeType());
				m.put("occurredAt", iso(c.getOccurredAt()));
				m.put("availableAt", iso(c.getRecordedAt()));
				m.put("description", c.getSummary());
				m.put("rootCauseConfirmed", c.isRootCauseConfirmed());
				return m;
			}).toList();
		String key = equipmentId == null ? "ALL" : equipmentId;
		return env(Map.of("items", rows.stream().limit(MAX_ITEMS).toList(), "truncated", rows.size() > MAX_ITEMS),
			List.of(ref("E-CHG-" + key, "CHANGE", key)),
			List.of("정비 기록이 있다는 것만으로 원인이 확정되지 않습니다(rootCauseConfirmed)."));
	}

	// ------------------------------------------------------------------ 협업 · 기록

	public Map<String, Object> owners(String processCode) {
		faults.maybeFail("get_process_owner");
		List<Map<String, Object>> items = userRepository.findAll().stream()
			.filter(u -> u.getRole() == Role.ENGINEER && u.getProcessScopes().contains(processCode))
			.sorted(Comparator.comparing((UserAccount u) -> MAINTENANCE_TEAM.equals(u.getTeam())))
			.map(u -> {
				Map<String, Object> m = new LinkedHashMap<>();
				m.put("userId", u.getUserId());
				m.put("processCode", processCode);
				m.put("kind", MAINTENANCE_TEAM.equals(u.getTeam()) ? "MAINTENANCE" : "PROCESS_OWNER");
				m.put("displayName", "(가림)"); // 이름은 모델에 보내지 않는다
				return m;
			}).toList();
		return env(Map.of("items", items), List.of(), List.of());
	}

	public Map<String, Object> checkRequests(String id) {
		faults.maybeFail("get_check_requests");
		Incident i = resolve(id);
		List<Map<String, Object>> rows = checkRequestRepository.findByIncidentIdOrderByCreatedAtAsc(i.getIncidentId())
			.stream().map(this::requestView).toList();
		List<Map<String, Object>> ev = rows.stream().filter(r -> r.get("reply") != null)
			.map(r -> ref("E-REQ-" + r.get("requestId"), "REPLY", (String) r.get("requestId"))).toList();
		return env(Map.of("items", rows), ev, List.of());
	}

	public Map<String, Object> actions(String id) {
		Incident i = resolve(id);
		return env(Map.of("items", actionRepository.findByIncidentIdOrderByPerformedAtAsc(i.getIncidentId()).stream()
			.map(InternalService::actionView).toList()), List.of(), List.of());
	}

	public Map<String, Object> reportContext(String id) {
		Incident i = resolve(id);
		Map<String, Object> data = new LinkedHashMap<>();
		data.put("incident", incidentView(i));
		data.put("checkRequests", checkRequestRepository.findByIncidentIdOrderByCreatedAtAsc(i.getIncidentId()).stream()
			.map(this::requestView).toList());
		data.put("actions", actionRepository.findByIncidentIdOrderByPerformedAtAsc(i.getIncidentId()).stream()
			.map(InternalService::actionView).toList());
		data.put("reports", reportRepository.findByIncidentIdOrderByVersionDesc(i.getIncidentId()).stream()
			.map(r -> Map.<String, Object>of("reportId", r.getReportId(), "version", r.getVersion(), "status",
				r.getStatus().name()))
			.toList());
		data.put("followupNote", "후속 확인 run 결과가 없으면 해결 완료로 보고하지 않습니다.");
		return env(data, List.of(ref("E-RPTCTX-" + i.getDisplayId(), "REPORT_CONTEXT", i.getDisplayId())), List.of());
	}

	// ------------------------------------------------------------------ 승인된 변경 실행 (approvalId + idempotencyKey만)

	@Transactional
	public Map<String, Object> executeApproved(ApprovalActionType type, String approvalId, String idempotencyKey) {
		if (approvalId == null || idempotencyKey == null || idempotencyKey.length() < 8) {
			throw InternalError.invalid("approvalId와 idempotencyKey(8자 이상)가 필요합니다.", "");
		}
		faults.maybeFail(switch (type) {
			case CHECK_REQUEST -> "create_cross_process_check_request";
			case MAINTENANCE_REQUEST -> "create_maintenance_request";
			case CONFIRM_REPORT -> "confirm_report";
			case RECORD_ACTION -> "record_action_result";
		});
		Ref ref = approvalService.executeApproved(approvalId, type, idempotencyKey);
		Object data = switch (type) {
			case CHECK_REQUEST, MAINTENANCE_REQUEST -> requestView(checkRequestRepository.findById(ref.id()).orElseThrow());
			case RECORD_ACTION -> actionView(actionRepository.findById(ref.id()).orElseThrow());
			case CONFIRM_REPORT -> reportView(reportRepository.findById(ref.id()).orElseThrow());
		};
		return env(data, List.of(), List.of());
	}

	// ------------------------------------------------------------------ Agent Worker가 부르는 그래프 제어

	/** job-message(contracts/schemas/job-message.schema.json) + 질문 — Kafka 메시지 대신 HTTP로 가져갈 때 쓴다 */
	public Map<String, Object> jobContext(String jobId) {
		Job job = jobService.load(jobId);
		Map<String, Object> resume = null;
		if (job.getKind() == JobKind.RESUME) {
			resume = new LinkedHashMap<>();
			resume.put("reason", job.getInput().getOrDefault("reason", "APPROVAL_DECIDED"));
			resume.put("refId", job.getInput().get("refId"));
			resume.put("interruptId", job.getInput().get("interruptId"));
			resume.put("parentJobId", job.getParentJobId());
		}
		Map<String, Object> msg = new LinkedHashMap<>();
		msg.put("schemaVersion", "1.0");
		msg.put("eventId", "EVT-" + jobId + "-A" + job.getAttempt());
		msg.put("jobId", jobId);
		msg.put("incidentId", job.getIncidentId());
		msg.put("threadId", job.getThreadId());
		msg.put("kind", job.getKind().name());
		msg.put("attempt", Math.max(1, Math.min(3, job.getAttempt())));
		msg.put("contextRef", "job-context://" + jobId);
		msg.put("resume", resume);
		msg.put("createdAt", iso(job.getCreatedAt()));
		msg.put("traceId", "TRACE-" + jobId);
		Map<String, Object> out = new LinkedHashMap<>();
		out.put("job", msg);
		out.put("question", job.getQuestion());
		out.put("scope", job.getScope());
		out.put("status", job.getStatus().name());
		out.put("analysisAsOf", iso(asOf()));
		return out;
	}

	public List<Map<String, Object>> queuedJobs() {
		return jobRepository.findByStatusOrderByCreatedAtAsc(JobStatus.QUEUED).stream()
			.map(j -> jobContext(j.getJobId())).toList();
	}

	@Transactional
	public Map<String, Object> claim(String jobId, int attempt, String workerId, int leaseSeconds) {
		Job job = jobService.load(jobId);
		if (job.getStatus() != JobStatus.QUEUED && job.getStatus() != JobStatus.RUNNING) {
			throw new InternalError("CONFLICT", "작업 " + jobId + "은 이미 " + job.getStatus() + " 상태입니다.",
				"중복 전달이면 건너뜁니다.");
		}
		Instant now = Instant.now();
		if (!job.claim(attempt, workerId, now, Duration.ofSeconds(leaseSeconds))) {
			throw new InternalError("CONFLICT", "작업 " + jobId + "(attempt " + attempt + ")은 이미 처리 중이거나 처리됐습니다.",
				"중복 전달이면 건너뜁니다.");
		}
		jobService.startExternal(job, incidentRepository.findById(job.getIncidentId()).orElseThrow());
		Map<String, Object> out = new LinkedHashMap<>();
		out.put("jobId", jobId);
		out.put("attempt", attempt);
		out.put("workerId", workerId);
		out.put("leaseUntil", iso(job.getLeaseUntil()));
		return out;
	}

	@Transactional
	public Map<String, Object> propose(String jobId, Map<String, Object> body) {
		Job job = jobService.load(jobId);
		Incident incident = incidentRepository.findById(job.getIncidentId()).orElseThrow();
		ApprovalActionType type = ApprovalActionType.parse(String.valueOf(body.get("actionType"))).orElseThrow(
			() -> InternalError.invalid("허용되지 않은 actionType: " + body.get("actionType"), String.join(" · ", ACTION_TYPES)));
		@SuppressWarnings("unchecked")
		Map<String, Object> payload = new LinkedHashMap<>((Map<String, Object>) body.getOrDefault("payload", Map.of()));
		payload.remove("incidentId");
		@SuppressWarnings("unchecked")
		List<String> evidenceIds = (List<String>) body.getOrDefault("evidenceIds", List.of());
		checkRecipient(type, payload, incident, job);
		Approval a = approvalService.createAgentProposal(incident, job, type, payload,
			String.valueOf(body.getOrDefault("rationale", "")), evidenceIds, (String) body.get("requestedBy"),
			(String) body.get("idempotencyKey"));
		return approvalView(a);
	}

	/**
	 * 받는 사람 검증 — 모델이 고른 담당자를 그대로 믿지 않는다(제안을 저장하기 전, backend 규칙).
	 * 앞 공정 확인 요청은 그 공정의 공정 담당자에게만, 정비 요청은 설비정비팀에게만, 질문한 본인에게는 보내지 않는다.
	 */
	private void checkRecipient(ApprovalActionType type, Map<String, Object> payload, Incident incident, Job job) {
		if (type != ApprovalActionType.CHECK_REQUEST && type != ApprovalActionType.MAINTENANCE_REQUEST) {
			return;
		}
		String ownerId = payload.get("ownerUserId") == null ? null : payload.get("ownerUserId").toString();
		UserAccount owner = ownerId == null ? null : userRepository.findById(ownerId).orElse(null);
		if (owner == null) {
			throw InternalError.invalid("담당자 " + ownerId + "가 없습니다.", "get_process_owner로 담당자 ID를 확인하세요.");
		}
		if (ownerId.equals(job.getRequestedBy())) {
			throw InternalError.invalid("질문한 사람(" + ownerId + ")에게 요청을 보낼 수 없습니다.",
				"다른 공정 담당자 또는 설비정비팀에게 보내거나, 요청 없이 결과로 정리하세요.");
		}
		boolean maintenance = MAINTENANCE_TEAM.equals(owner.getTeam());
		String target = payload.get("targetProcessCode") == null ? null : payload.get("targetProcessCode").toString();
		if (type == ApprovalActionType.CHECK_REQUEST) {
			if (target == null || target.equals(incident.getProcessCode())) {
				throw InternalError.invalid("앞 공정 확인 요청은 사건 공정(" + incident.getProcessCode() + ")이 아닌 앞 공정에 보냅니다.",
					"현재 공정 설비 점검은 propose_maintenance_request를 쓰세요.");
			}
			if (maintenance || !owner.getProcessScopes().contains(target)) {
				throw InternalError.invalid(ownerId + "는 " + target + " 공정 담당자가 아닙니다.",
					"get_process_owner(processCode=" + target + ")의 kind=PROCESS_OWNER userId를 쓰세요.");
			}
		}
		else {
			String proc = target == null ? incident.getProcessCode() : target;
			if (!maintenance || !owner.getProcessScopes().contains(proc)) {
				throw InternalError.invalid(ownerId + "는 설비정비팀이 아닙니다.",
					"get_process_owner(processCode=" + proc + ")의 kind=MAINTENANCE userId를 쓰세요.");
			}
		}
	}

	public Map<String, Object> approval(String approvalId) {
		return approvalView(approvalService.load(approvalId));
	}

	public Map<String, Object> checkRequest(String requestId) {
		return requestView(checkRequestRepository.findById(requestId)
			.orElseThrow(() -> InternalError.notFound("요청 " + requestId + "가 없습니다.", "")));
	}

	@Transactional
	public Map<String, Object> reportDraft(String id, Map<String, Object> body) {
		Incident i = resolve(id);
		@SuppressWarnings("unchecked")
		Map<String, Object> sections = (Map<String, Object>) body.get("sections");
		List<String> missing = ReportSections.TITLES.keySet().stream()
			.filter(k -> sections == null || sections.get(k) == null).toList();
		if (!missing.isEmpty()) {
			throw InternalError.invalid("보고서 섹션이 빠졌습니다: " + missing, String.join(" · ", ReportSections.TITLES.keySet()));
		}
		@SuppressWarnings("unchecked")
		List<String> evidenceIds = (List<String>) body.getOrDefault("evidenceIds", List.of());
		String jobId = jobRepository.findByIncidentIdOrderByCreatedAtDesc(i.getIncidentId()).stream()
			.filter(j -> j.getKind() == JobKind.REPORT && j.getStatus() == JobStatus.RUNNING).map(Job::getJobId)
			.findFirst().orElse(null);
		Report r = reportService.createAgentDraft(i, Json.convert(sections, ReportSections.class), evidenceIds, jobId, "agent");
		return reportView(r);
	}

	// ------------------------------------------------------------------ 모양 맞추기

	Incident resolve(String id) {
		return incidentRepository.findById(id).or(() -> incidentRepository.findByDisplayId(id))
			.orElseThrow(() -> InternalError.notFound("사건 " + id + "를 찾을 수 없습니다.",
				"사건 ID는 EV-1009 같은 표시 ID 또는 inc_로 시작하는 ID입니다."));
	}

	private Instant asOf() {
		return evidence.defaultAsOf();
	}

	private RouteRun loadRun(String runId) {
		RouteRun r = routeRepository.findByRunId(runId).orElseThrow(() -> InternalError.notFound("run " + runId + "가 없습니다.",
			"예: RUN-W-CURRENT-009-3-CMP — get_wafer_lineage로 웨이퍼의 runId를 먼저 확인하세요."));
		if (r.getStartedAt().isAfter(asOf())) {
			throw new InternalError("FORBIDDEN", "run " + runId + "은 분석 시점(" + iso(asOf()) + ") 이후에 시작했습니다.",
				"분석 시점 이후 자료(FOLLOWUP 등)는 현재 판단에 쓰지 않습니다.");
		}
		return r;
	}

	private void requireRunEnded(RouteRun r) {
		if (r.getEndedAt().isAfter(asOf())) {
			throw new InternalError("FORBIDDEN", "run " + r.getRunId() + "이 분석 시점에 끝나지 않았습니다.", "");
		}
	}

	private String sensorList(RouteRun r) {
		return String.join(", ", pack.sensors().stream().filter(s -> r.getProcessCode().equals(s.get("process")))
			.map(s -> String.valueOf(s.get("sensorId"))).toList());
	}

	private Map<String, Object> incidentView(Incident i) {
		Map<String, Object> m = new LinkedHashMap<>();
		m.put("incidentId", i.getIncidentId());
		m.put("displayId", i.getDisplayId());
		m.put("title", i.getTitle());
		m.put("anomalyType", i.getAnomalyType());
		m.put("processCode", i.getProcessCode());
		m.put("equipmentId", i.getEquipmentId());
		m.put("lotId", i.getLotId());
		m.put("waferIds", List.copyOf(i.getWaferIds()));
		m.put("runIds", List.copyOf(i.getRunIds()));
		m.put("detectedAt", iso(i.getDetectedAt()));
		m.put("status", i.getStatus().name());
		m.put("summary", i.getSummary());
		m.put("metrics", i.getMetrics());
		m.put("analysisAsOf", iso(asOf()));
		return m;
	}

	private Map<String, Object> requestView(CheckRequest r) {
		Map<String, Object> m = new LinkedHashMap<>();
		m.put("requestId", r.getRequestId());
		m.put("requestType", r.getRequestType());
		m.put("incidentId", r.getIncidentId());
		m.put("ownerUserId", r.getOwnerUserId());
		m.put("requesterUserId", r.getRequesterUserId());
		m.put("targetProcessCode", r.getTargetProcessCode());
		m.put("equipmentId", r.getEquipmentId());
		m.put("question", r.getQuestion());
		m.put("causeIds", List.copyOf(r.getCauseIds()));
		m.put("brief", r.getBrief());
		m.put("evidenceIds", List.copyOf(r.getEvidenceIds()));
		m.put("status", r.getStatus().name());
		m.put("version", r.getVersion());
		m.put("createdAt", iso(r.getCreatedAt()));
		List<Reply> replies = replyRepository.findByRequestIdOrderByCreatedAtAsc(r.getRequestId());
		m.put("reply", replies.isEmpty() ? null : replyView(replies.getLast()));
		return m;
	}

	private static Map<String, Object> replyView(Reply p) {
		Map<String, Object> m = new LinkedHashMap<>();
		m.put("replyId", p.getReplyId());
		m.put("requestId", p.getRequestId());
		m.put("authorUserId", p.getAuthorUserId());
		m.put("findings", p.getFindings());
		m.put("memo", "");
		m.put("evidenceIds", List.copyOf(p.getEvidenceIds()));
		m.put("confirmedByUser", p.isConfirmedByUser());
		m.put("createdAt", iso(p.getCreatedAt()));
		return m;
	}

	private static Map<String, Object> actionView(IncidentAction a) {
		Map<String, Object> m = new LinkedHashMap<>();
		m.put("actionId", a.getActionId());
		m.put("incidentId", a.getIncidentId());
		m.put("actionType", a.getActionType());
		m.put("description", a.getDescription());
		m.put("performedAt", iso(a.getPerformedAt()));
		m.put("performedBy", a.getPerformedBy());
		m.put("verificationStatus", a.getVerificationStatus().name());
		m.put("createdAt", iso(a.getCreatedAt()));
		return m;
	}

	/** contracts Report 모양 */
	static Map<String, Object> reportView(Report r) {
		Map<String, Object> m = new LinkedHashMap<>();
		m.put("reportId", r.getReportId());
		m.put("incidentId", r.getIncidentId());
		m.put("version", r.getVersion());
		m.put("contentRevision", r.getContentRevision());
		m.put("status", r.getStatus().name());
		m.put("sections", r.sectionMap()); // 팀 계약 Report.sections — 다섯 키 객체(공개 API · DB는 배열)
		m.put("evidenceIds", List.copyOf(r.getEvidenceIds()));
		m.put("sourceRevision", r.getSourceRevision());
		m.put("snapshotRef", r.getSnapshotRef());
		m.put("createdAt", iso(r.getCreatedAt()));
		return m;
	}

	private static Map<String, Object> approvalView(Approval a) {
		Map<String, Object> m = new LinkedHashMap<>();
		m.put("approvalId", a.getApprovalId());
		m.put("incidentId", a.getIncidentId());
		m.put("jobId", a.getJobId());
		m.put("actionType", a.getActionType().internalName()); // 내부 API는 팀 계약 이름
		m.put("payload", a.getPayload());
		m.put("rationale", a.getRationale());
		m.put("evidenceIds", List.copyOf(a.getEvidenceIds()));
		m.put("actionHash", a.getActionHash());
		m.put("status", a.getStatus().name());
		m.put("version", a.getVersion());
		m.put("expiresAt", iso(a.getExpiresAt()));
		m.put("decidedBy", a.getDecidedBy());
		m.put("comment", a.getDecisionComment());
		m.put("executionRef", a.executionRef());
		m.put("resumeJobId", a.getResumeJobId());
		return m;
	}

	private Map<String, Object> env(Object data, List<Map<String, Object>> evidenceRefs, List<String> warnings) {
		Map<String, Object> m = new LinkedHashMap<>();
		m.put("data", data);
		m.put("evidenceRefs", evidenceRefs);
		m.put("dataVersion", dataVersion.current());
		m.put("warnings", warnings);
		m.put("synthetic", true);
		return m;
	}

	private Map<String, Object> ref(String evidenceId, String sourceType, String sourceId) {
		Map<String, Object> m = new LinkedHashMap<>();
		m.put("evidenceId", evidenceId);
		m.put("sourceType", sourceType);
		m.put("sourceId", sourceId);
		m.put("dataVersion", dataVersion.current());
		m.put("synthetic", true);
		return m;
	}

	static String iso(Instant t) {
		return t == null ? null : t.atZone(KST).toOffsetDateTime().withNano(0).toString();
	}

	static List<String> csv(String v) {
		if (v == null || v.isBlank()) {
			return null;
		}
		return java.util.Arrays.stream(v.split(",")).map(String::trim).filter(s -> !s.isEmpty()).toList();
	}
}
