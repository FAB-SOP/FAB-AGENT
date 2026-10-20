package com.fabcontext.api.domain.approval;

import java.time.Clock;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.fabcontext.api.auth.AuthUser;
import com.fabcontext.api.auth.UserAccount;
import com.fabcontext.api.auth.UserAccountRepository;
import com.fabcontext.api.common.ApiException;
import com.fabcontext.api.common.Cursors;
import com.fabcontext.api.common.Hashing;
import com.fabcontext.api.common.Ids;
import com.fabcontext.api.common.PageResponse;
import com.fabcontext.api.common.Ref;
import com.fabcontext.api.config.FabProperties;
import com.fabcontext.api.domain.action.ActionService;
import com.fabcontext.api.domain.approval.ApprovalDtos.ApprovalDecision;
import com.fabcontext.api.domain.approval.ApprovalDtos.ApprovalDecisionResult;
import com.fabcontext.api.domain.approval.ApprovalDtos.ApprovalSummary;
import com.fabcontext.api.domain.approval.ApprovalDtos.ApprovalView;
import com.fabcontext.api.domain.approval.ApprovalDtos.Proposal;
import com.fabcontext.api.domain.checkrequest.CheckRequest;
import com.fabcontext.api.domain.checkrequest.CheckRequestService;
import com.fabcontext.api.domain.checkrequest.RequestBrief;
import com.fabcontext.api.domain.incident.Incident;
import com.fabcontext.api.domain.incident.IncidentRepository;
import com.fabcontext.api.domain.incident.IncidentService;
import com.fabcontext.api.domain.job.IncidentStreamHub;
import com.fabcontext.api.domain.job.Job;
import com.fabcontext.api.domain.job.JobService;
import com.fabcontext.api.domain.job.JobStatus;
import com.fabcontext.api.domain.report.ReportService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class ApprovalService {

	private final ApprovalRepository repository;
	private final IncidentService incidentService;
	private final IncidentRepository incidentRepository;
	private final UserAccountRepository userRepository;
	private final JobService jobService;
	private final CheckRequestService checkRequestService;
	private final ActionService actionService;
	private final ReportService reportService;
	private final IncidentStreamHub streamHub;
	private final FabProperties properties;
	private final Clock clock;

	@Transactional
	public PageResponse<ApprovalSummary> list(AuthUser user, String status, String incidentId, String cursor,
			Integer size) {
		Instant now = Instant.now(clock);
		List<Approval> all = repository.findByProcessCodeInOrderByCreatedAtDesc(user.processScopes());
		all.forEach(a -> a.expireIfDue(now));
		List<Approval> filtered = all.stream()
			.filter(a -> status == null || a.getStatus().name().equalsIgnoreCase(status))
			.filter(a -> incidentId == null || incidentId.equals(a.getIncidentId()))
			.toList();
		return Cursors.page(filtered, cursor, size, ApprovalSummary::from);
	}

	@Transactional
	public ApprovalView get(String approvalId, AuthUser user) {
		Approval approval = loadForUser(approvalId, user);
		approval.expireIfDue(Instant.now(clock));
		return ApprovalView.from(approval);
	}

	/** 수동 제안 (P07/P08). 실제 업무 등록 전 단계이며 승인 후에만 실행된다. */
	@Transactional
	public ApprovalView createProposal(String incidentId, AuthUser user, Proposal body) {
		Incident incident = incidentService.loadForUser(incidentId, user);
		incident.requireNotClosed();
		incident.checkVersion(body.expectedIncidentVersion());
		if (body.jobId() != null) {
			Job job = jobService.load(body.jobId());
			if (!job.getIncidentId().equals(incidentId)) {
				throw ApiException.unprocessable("JOB_INCIDENT_MISMATCH", "jobId가 이 사건의 작업이 아닙니다.");
			}
		}
		Approval approval = create(incident, body.actionType(), body.payload(), body.rationale(), body.evidenceIds(),
			body.jobId(), "USER", user.userId());
		return ApprovalView.from(approval);
	}

	/** Agent 내부 경로: 분석 중 제안을 저장하고 작업은 WAITING_APPROVAL로 대기한다. */
	public Approval createAgentProposal(Incident incident, Job job, ApprovalActionType type,
			Map<String, Object> payload, String rationale, List<String> evidenceIds) {
		return create(incident, type, payload, rationale, evidenceIds, job.getJobId(), "AGENT", "agent");
	}

	@Transactional
	public ApprovalDecisionResult decide(String approvalId, AuthUser user, ApprovalDecision body) {
		Approval approval = loadForUser(approvalId, user);
		Instant now = Instant.now(clock);
		if (approval.expireIfDue(now)) {
			throw ApiException.conflict("APPROVAL_EXPIRED", "만료된 제안입니다. 최신 내용을 확인하고 필요하면 새 제안을 만드세요.",
				Map.of("status", approval.getStatus().name(), "currentVersion", approval.getVersion()));
		}
		approval.requirePending();
		ApiException.checkVersion("approval", body.expectedVersion(), approval.getVersion());
		if (body.actionHash() != null && !body.actionHash().equals(approval.getActionHash())) {
			throw ApiException.conflict("ACTION_HASH_MISMATCH", "검토한 내용과 저장된 실행 내용이 다릅니다. 다시 조회하세요.",
				Map.of("actionHash", approval.getActionHash()));
		}
		Incident incident = incidentRepository.findById(approval.getIncidentId()).orElseThrow();
		Job waitingJob = approval.getJobId() == null ? null : jobService.load(approval.getJobId());
		boolean agentWaiting = waitingJob != null && waitingJob.getStatus() == JobStatus.WAITING_APPROVAL
				&& approvalId.equals(waitingJob.getWaitingRefId());

		if ("REJECT".equals(body.decision())) {
			if (body.comment() == null || body.comment().isBlank()) {
				throw ApiException.unprocessable("REJECT_REASON_REQUIRED", "거절 사유(comment)를 입력하세요.");
			}
			approval.reject(user.userId(), body.comment(), now);
			String resumeJobId = null;
			if (agentWaiting && properties.agent().external()) {
				// 거절도 실행하지 않는다. 외부 Agent는 같은 thread로 재개해 거절 사유를 읽고 지금 근거로 결과를 정리한다
				// (재개하지 않으면 Agent 체크포인트가 승인 대기에 남아 다음 질문을 받을 수 없다).
				resumeJobId = jobService.resume(waitingJob, incident, user.userId(), resumeInput(approvalId, waitingJob))
					.getJobId();
			}
			else if (agentWaiting) {
				// 시뮬레이터: 거절 결과로 조사를 끝낸다
				jobService.succeed(waitingJob, incident, new Ref("APPROVAL_REJECTED", approvalId));
			}
			incident.record(now, "APPROVAL_REJECTED", user.userId(),
				approval.getActionType() + " 제안 거절: " + body.comment(), "APPROVAL", approvalId);
			streamHub.publish(incident.getIncidentId(), "approval.decided",
				Map.of("approvalId", approvalId, "decision", "REJECT"));
			return new ApprovalDecisionResult(ApprovalView.from(approval), resumeJobId, null);
		}

		incident.requireNotClosed();
		if (agentWaiting) {
			// Agent가 기다리는 제안: 승인은 기록만 한다. 실행은 재개된 Agent가 승인 ID로
			// /internal/approved-actions/*를 불러 하고, 그때 상태 · 만료 · 저장된 payload를 다시 확인한다.
			String resumeJobId = jobService.resume(waitingJob, incident, user.userId(), resumeInput(approvalId, waitingJob))
				.getJobId();
			approval.approve(user.userId(), body.comment(), null, resumeJobId, now);
			incident.record(now, "APPROVAL_APPROVED", user.userId(), approval.getActionType() + " 제안 승인 → Agent 재개 후 실행",
				"APPROVAL", approvalId);
			streamHub.publish(incident.getIncidentId(), "approval.decided",
				Map.of("approvalId", approvalId, "decision", "APPROVE", "resumeJobId", resumeJobId));
			return new ApprovalDecisionResult(ApprovalView.from(approval), resumeJobId, null);
		}
		// 수동 제안: 기다리는 Agent가 없으므로 권한 · 만료 · 버전을 재검증한 뒤 저장된 payload를 바로 실행한다.
		Ref execution = execute(approval, incident, user.userId(), null);
		approval.approve(user.userId(), body.comment(), execution, null, now);
		approval.markExecuted(execution, "decision:" + approvalId, now);
		incident.record(now, "APPROVAL_APPROVED", user.userId(), approval.getActionType() + " 제안 승인 → 실행",
			execution.type(), execution.id());
		streamHub.publish(incident.getIncidentId(), "approval.decided",
			Map.of("approvalId", approvalId, "decision", "APPROVE", "executionRef", execution));
		return new ApprovalDecisionResult(ApprovalView.from(approval), null, execution);
	}

	private static Map<String, Object> resumeInput(String approvalId, Job waitingJob) {
		Map<String, Object> input = new LinkedHashMap<>();
		input.put("trigger", "APPROVAL");
		input.put("approvalId", approvalId);
		input.put("reason", "APPROVAL_DECIDED");
		input.put("refId", approvalId);
		input.put("interruptId", waitingJob.getInput().get("interruptId"));
		return input;
	}

	/**
	 * 승인된 변경 실행 — Agent(업무 MCP의 변경 도구)와 시뮬레이터가 같은 경로를 쓴다.
	 * 승인 ID와 멱등 키만 받고, 실행 내용은 승인 때 저장한 payload뿐이다. 이미 실행했으면 같은 결과를 돌려준다.
	 */
	@Transactional
	public Ref executeApproved(String approvalId, ApprovalActionType expected, String idempotencyKey) {
		Approval approval = repository.findById(approvalId).orElseThrow(() -> ApiException.notFound("approval", approvalId));
		if (approval.getActionType() != expected) {
			throw ApiException.forbidden("APPROVAL_ACTION_MISMATCH",
				"승인 " + approvalId + "는 " + approval.getActionType() + " 실행용입니다.");
		}
		if (approval.isExecuted()) {
			return approval.executionRef();
		}
		Instant now = Instant.now(clock);
		if (approval.getStatus() != ApprovalStatus.APPROVED) {
			approval.expireIfDue(now);
			throw ApiException.conflict("APPROVAL_REQUIRED", "승인 " + approvalId + "가 " + approval.getStatus()
					+ " 상태입니다. 사람의 승인 전에는 실행할 수 없습니다.", Map.of("status", approval.getStatus().name()));
		}
		if (approval.getExpiresAt() != null && now.isAfter(approval.getExpiresAt())) {
			throw ApiException.conflict("APPROVAL_EXPIRED", "승인 " + approvalId + "의 실행 기한이 지났습니다. 새로 제안하세요.",
				Map.of("expiresAt", approval.getExpiresAt().toString()));
		}
		Incident incident = incidentRepository.findById(approval.getIncidentId()).orElseThrow();
		incident.requireNotClosed();
		String threadId = approval.getJobId() == null ? null : jobService.load(approval.getJobId()).getThreadId();
		Ref execution = execute(approval, incident, approval.getDecidedBy(), threadId);
		approval.markExecuted(execution, idempotencyKey, now);
		incident.record(now, "APPROVAL_EXECUTED", "agent", approval.getActionType() + " 승인된 변경 실행", execution.type(),
			execution.id());
		streamHub.publish(incident.getIncidentId(), "approval.executed",
			Map.of("approvalId", approvalId, "executionRef", execution));
		return execution;
	}

	/** Agent 내부 경로의 멱등 제안 — 같은 키로 다시 오면 처음 만든 승인을 돌려준다 */
	@Transactional
	public Approval createAgentProposal(Incident incident, Job job, ApprovalActionType type, Map<String, Object> payload,
			String rationale, List<String> evidenceIds, String requestedBy, String idempotencyKey) {
		if (idempotencyKey != null) {
			var existing = repository.findByProposalKey(idempotencyKey);
			if (existing.isPresent()) {
				return existing.get();
			}
		}
		Approval approval = create(incident, type, payload, rationale, evidenceIds, job.getJobId(), "AGENT",
			requestedBy == null ? "agent" : requestedBy);
		approval.assignProposalKey(idempotencyKey);
		return approval;
	}

	@Transactional(readOnly = true)
	public Approval load(String approvalId) {
		return repository.findById(approvalId).orElseThrow(() -> ApiException.notFound("approval", approvalId));
	}

	private Ref execute(Approval approval, Incident incident, String actorId, String threadId) {
		Map<String, Object> payload = approval.getPayload();
		return switch (approval.getActionType()) {
			case CHECK_REQUEST, MAINTENANCE_REQUEST -> {
				CheckRequest request = checkRequestService.createFromApproval(incident, approval.getApprovalId(),
					payload, approval.getEvidenceIds(), actorId, threadId,
					approval.getActionType() == ApprovalActionType.MAINTENANCE_REQUEST ? "MAINTENANCE" : "CROSS_PROCESS");
				yield new Ref("CHECK_REQUEST", request.getRequestId());
			}
			case RECORD_ACTION -> new Ref("ACTION", actionService.recordFromApproval(incident, approval.getApprovalId(),
				payload, approval.getEvidenceIds(), actorId).getActionId());
			case CONFIRM_REPORT -> new Ref("REPORT",
				reportService.confirmFromApproval(incident, payload, actorId).getReportId());
		};
	}

	private Approval create(Incident incident, ApprovalActionType type, Map<String, Object> rawPayload,
			String rationale, List<String> evidenceIds, String jobId, String source, String createdBy) {
		Map<String, Object> payload = normalize(type, rawPayload, incident);
		Map<String, Object> hashed = new LinkedHashMap<>();
		hashed.put("actionType", type.name());
		hashed.put("incidentId", incident.getIncidentId());
		hashed.put("payload", payload);
		Instant now = Instant.now(clock);
		Approval approval = Approval.builder()
			.approvalId(Ids.next("apv"))
			.incidentId(incident.getIncidentId())
			.processCode(incident.getProcessCode())
			.actionType(type)
			.payload(payload)
			.rationale(rationale)
			.evidenceIds(evidenceIds)
			.actionHash(Hashing.canonicalSha256(hashed))
			.expiresAt(now.plus(properties.approvalTtl()))
			.jobId(jobId)
			.source(source)
			.createdBy(createdBy)
			.createdAt(now)
			.incidentVersionAtProposal((int) incident.getVersion())
			.build();
		approval = repository.save(approval);
		incident.record(now, "PROPOSAL_CREATED", createdBy, type + " 제안 생성 (승인 대기)", "APPROVAL",
			approval.getApprovalId());
		streamHub.publish(incident.getIncidentId(), "approval.created",
			Map.of("approvalId", approval.getApprovalId(), "actionType", type.name(), "source", source));
		return approval;
	}

	/** actionType별 payload 검증 · 정규화. 승인 시 이 값만 실행된다. */
	private Map<String, Object> normalize(ApprovalActionType type, Map<String, Object> raw, Incident incident) {
		Map<String, Object> p = new LinkedHashMap<>();
		switch (type) {
			case CHECK_REQUEST, MAINTENANCE_REQUEST -> {
				String ownerUserId = requireString(raw, "ownerUserId");
				UserAccount owner = userRepository.findById(ownerUserId)
					.orElseThrow(() -> ApiException.unprocessable("OWNER_NOT_FOUND", "담당자를 찾을 수 없습니다: " + ownerUserId));
				p.put("ownerUserId", ownerUserId);
				p.put("question", requireString(raw, "question"));
				Object target = raw.get("targetProcessCode");
				p.put("targetProcessCode", target != null ? target.toString()
						: owner.getProcessScopes().isEmpty() ? null : owner.getProcessScopes().getFirst());
				if (raw.get("dueAt") != null) {
					p.put("dueAt", parseInstant(raw.get("dueAt"), "dueAt").toString());
				}
				// 요청 대상 설비 · 원인 후보 · 요청 브리프도 실행 내용이다 — 승인 해시에 들어가고 확인 요청에 그대로 복사된다
				if (raw.get("equipmentId") instanceof String eq && !eq.isBlank()) {
					p.put("equipmentId", eq);
				}
				if (raw.get("causeIds") instanceof List<?> causeIds && !causeIds.isEmpty()) {
					p.put("causeIds", causeIds.stream().map(String::valueOf).toList());
				}
				if (raw.get("brief") != null) {
					p.put("brief", RequestBrief.validate(raw.get("brief")));
				}
			}
			case RECORD_ACTION -> {
				p.put("actionType", requireString(raw, "actionType"));
				p.put("description", requireString(raw, "description"));
				Instant performedAt = parseInstant(raw.get("performedAt"), "performedAt");
				if (performedAt.isAfter(Instant.now(clock).plusSeconds(300))) {
					throw ApiException.unprocessable("PERFORMED_AT_IN_FUTURE", "실제로 수행한 조치만 기록할 수 있습니다.");
				}
				p.put("performedAt", performedAt.toString());
				if (raw.get("performedBy") != null) {
					p.put("performedBy", raw.get("performedBy").toString());
				}
			}
			case CONFIRM_REPORT -> {
				String reportId = requireString(raw, "reportId");
				reportService.loadDraftOfIncident(reportId, incident.getIncidentId());
				Object revision = raw.get("expectedContentRevision");
				if (!(revision instanceof Number n)) {
					throw ApiException.unprocessable("INVALID_PAYLOAD", "payload.expectedContentRevision(숫자)이 필요합니다.");
				}
				p.put("reportId", reportId);
				p.put("expectedContentRevision", n.longValue());
			}
		}
		return p;
	}

	private static String requireString(Map<String, Object> raw, String key) {
		Object value = raw.get(key);
		if (value == null || value.toString().isBlank()) {
			throw ApiException.unprocessable("INVALID_PAYLOAD", "payload." + key + "이(가) 필요합니다.");
		}
		return value.toString();
	}

	private static Instant parseInstant(Object value, String key) {
		if (value == null) {
			throw ApiException.unprocessable("INVALID_PAYLOAD", "payload." + key + "이(가) 필요합니다.");
		}
		try {
			return Instant.parse(value.toString());
		}
		catch (DateTimeParseException e) {
			throw ApiException.unprocessable("INVALID_PAYLOAD", "payload." + key + "는 ISO-8601 시각이어야 합니다.");
		}
	}

	private Approval loadForUser(String approvalId, AuthUser user) {
		Approval approval = repository.findById(approvalId)
			.orElseThrow(() -> ApiException.notFound("approval", approvalId));
		incidentService.loadForUser(approval.getIncidentId(), user);
		return approval;
	}
}
