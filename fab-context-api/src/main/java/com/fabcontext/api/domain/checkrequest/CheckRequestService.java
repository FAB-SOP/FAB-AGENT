package com.fabcontext.api.domain.checkrequest;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import com.fabcontext.api.auth.AuthUser;
import com.fabcontext.api.common.ApiException;
import com.fabcontext.api.common.Cursors;
import com.fabcontext.api.common.Ids;
import com.fabcontext.api.common.PageResponse;
import com.fabcontext.api.domain.checkrequest.CheckRequestDtos.AcceptInput;
import com.fabcontext.api.domain.checkrequest.CheckRequestDtos.CheckRequestSummary;
import com.fabcontext.api.domain.checkrequest.CheckRequestDtos.DeclineInput;
import com.fabcontext.api.domain.checkrequest.CheckRequestDtos.CheckRequestView;
import com.fabcontext.api.domain.checkrequest.CheckRequestDtos.IncidentContext;
import com.fabcontext.api.domain.checkrequest.CheckRequestDtos.ReplyInput;
import com.fabcontext.api.domain.checkrequest.CheckRequestDtos.ReplyResult;
import com.fabcontext.api.domain.checkrequest.CheckRequestDtos.ReplyView;
import com.fabcontext.api.domain.checkrequest.CheckRequestRepositories.CheckRequestRepository;
import com.fabcontext.api.domain.checkrequest.CheckRequestRepositories.ReplyRepository;
import com.fabcontext.api.domain.incident.Incident;
import com.fabcontext.api.domain.incident.IncidentRepository;
import com.fabcontext.api.domain.incident.IncidentStatus;
import com.fabcontext.api.common.stream.IncidentStreamHub;
import com.fabcontext.api.domain.job.Job;
import com.fabcontext.api.domain.job.JobRepository;
import com.fabcontext.api.domain.job.JobService;
import com.fabcontext.api.domain.job.JobStatus;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class CheckRequestService {

	private final CheckRequestRepository requestRepository;
	private final ReplyRepository replyRepository;
	private final IncidentRepository incidentRepository;
	private final JobRepository jobRepository;
	private final JobService jobService;
	private final IncidentStreamHub streamHub;
	private final Clock clock;
	private final com.fabcontext.api.domain.work.WorkItemRepository workItems;

	/** 승인된 CHECK_REQUEST · MAINTENANCE_REQUEST 제안의 저장된 payload로 요청을 발송한다. */
	public CheckRequest createFromApproval(Incident incident, String approvalId, Map<String, Object> payload,
			List<String> evidenceIds, String requesterUserId, String threadId) {
		return createFromApproval(incident, approvalId, payload, evidenceIds, requesterUserId, threadId, "CROSS_PROCESS");
	}

	/** requestType: CROSS_PROCESS(다른 공정 담당 확인) · MAINTENANCE(같은 공정 정비 담당 점검) */
	public CheckRequest createFromApproval(Incident incident, String approvalId, Map<String, Object> payload,
			List<String> evidenceIds, String requesterUserId, String threadId, String requestType) {
		Instant now = Instant.now(clock);
		CheckRequest request = CheckRequest.builder()
			.requestId(Ids.next("chk"))
			.requestType(requestType)
			.incidentId(incident.getIncidentId())
			.processCode(incident.getProcessCode())
			.targetProcessCode((String) payload.get("targetProcessCode"))
			.approvalId(approvalId)
			.question((String) payload.get("question"))
			.evidenceIds(evidenceIds)
			.requesterUserId(requesterUserId)
			.ownerUserId((String) payload.get("ownerUserId"))
			.threadId(threadId)
			.dueAt(payload.get("dueAt") == null ? null : Instant.parse(payload.get("dueAt").toString()))
			.equipmentId((String) payload.get("equipmentId"))
			.causeIds(payload.get("causeIds") instanceof List<?> ids ? ids.stream().map(String::valueOf).toList() : null)
			.brief(payload.get("brief") instanceof Map<?, ?> b ? castMap(b) : null)
			.createdAt(now)
			.build();
		request = requestRepository.save(request);
		if (incident.getStatus() == IncidentStatus.OPEN || incident.getStatus() == IncidentStatus.INVESTIGATING) {
			incident.setStatus(IncidentStatus.WAITING_REPLY);
		}
		incident.record(now, "CHECK_REQUEST_SENT", requesterUserId,
			"확인 요청 발송 → " + request.getOwnerUserId() + ": " + request.getQuestion(), "CHECK_REQUEST",
			request.getRequestId());
		streamHub.publish(incident.getIncidentId(), "check-request.created",
			Map.of("requestId", request.getRequestId(), "ownerUserId", request.getOwnerUserId()));
		return request;
	}

	@Transactional(readOnly = true)
	public PageResponse<CheckRequestSummary> list(AuthUser user, String box, String status, String incidentId,
			String cursor, Integer size) {
		String mode = box == null ? "received" : box.toLowerCase();
		List<CheckRequest> source = switch (mode) {
			case "received" -> requestRepository.findByOwnerUserIdOrderByCreatedAtDesc(user.userId());
			case "sent" -> requestRepository.findByRequesterUserIdOrderByCreatedAtDesc(user.userId());
			default -> throw ApiException.badRequest("INVALID_BOX", "box는 received 또는 sent입니다.");
		};
		List<CheckRequest> filtered = source.stream()
			.filter(r -> status == null || r.getStatus().name().equalsIgnoreCase(status))
			.filter(r -> incidentId == null || incidentId.equals(r.getIncidentId()))
			.toList();
		return Cursors.page(filtered, cursor, size, r -> CheckRequestSummary.from(r, displayId(r.getIncidentId())));
	}

	@Transactional(readOnly = true)
	public List<CheckRequestView> forIncident(String id, AuthUser user) {
		Incident incident=incidentRepository.findById(id).orElseThrow(()->ApiException.notFound("incident",id));
		if(!user.hasScope(incident.getProcessCode())) throw ApiException.forbidden("PROCESS_SCOPE_REQUIRED","사건 공정의 조회 권한이 필요합니다.");
		return requestRepository.findByIncidentIdOrderByCreatedAtAsc(id).stream().map(this::view).toList();
	}

	@Transactional(readOnly = true)
	public CheckRequestView get(String requestId, AuthUser user) {
		CheckRequest request = loadVisible(requestId, user);
		return view(request);
	}

	@Transactional
	public ReplyResult reply(String requestId, AuthUser user, ReplyInput input) {
		CheckRequest request = loadVisible(requestId, user);
		if (!user.userId().equals(request.getOwnerUserId())) {
			throw ApiException.forbidden("NOT_REQUEST_OWNER", "이 요청의 담당자만 회신할 수 있습니다.");
		}
		if (!Boolean.TRUE.equals(input.confirmedByUser())) {
			throw ApiException.unprocessable("USER_CONFIRMATION_REQUIRED", "담당자가 직접 확인했음을 confirmedByUser=true로 표시해야 합니다.");
		}
		ApiException.checkVersion("checkRequest", input.expectedVersion(), request.getVersion());
		Instant now = Instant.now(clock);
		request.markReplied(now);
		Incident incident = incidentRepository.findById(request.getIncidentId())
			.orElseThrow(() -> ApiException.notFound("incident", request.getIncidentId()));

		Reply reply = Reply.builder()
			.replyId(Ids.next("rpl"))
			.requestId(requestId)
			.incidentId(incident.getIncidentId())
			.findings(input.findings())
			.evidenceIds(input.evidenceIds() == null ? List.of() : input.evidenceIds())
			.authorUserId(user.userId())
			.confirmedByUser(true)
			.createdAt(now)
			.build();

		String resumeJobId = null;
		if (request.getWaitingJobId() != null) {
			Job waiting = jobRepository.findById(request.getWaitingJobId()).orElse(null);
			if (waiting != null && waiting.getStatus() == JobStatus.WAITING_REPLY) {
				Job resume = jobService.resume(waiting, incident, user.userId(),
					resumeInput(waiting, requestId, reply.getReplyId()));
				resumeJobId = resume.getJobId();
			}
		}
		// 새 업무 요청은 중단된 Agent가 없으므로 회신을 반영하는 새 분석을 시작한다.
		// 진행 중 분석이 있으면 회신 저장을 우선하고 그 분석의 조회 자료로 제공한다.
		var linkedWork = workItems.findByRequestId(requestId);
		if (resumeJobId == null && linkedWork.isPresent()
				&& incident.getStatus() != IncidentStatus.CLOSED
				&& !jobRepository.existsByIncidentIdAndStatusIn(incident.getIncidentId(),
					java.util.EnumSet.of(JobStatus.QUEUED, JobStatus.RUNNING))) {
			Job followup = jobService.enqueue(incident, com.fabcontext.api.domain.job.JobKind.DEEP,
				request.getRequesterUserId(), "담당자 회신을 반영해 원인 후보와 후속 확인 사항을 다시 분석해 주세요. 요청: "
					+ request.getQuestion() + " / 회신: " + input.findings(), "ALL",
				Map.of("trigger", "WORK_REPLY", "requestId", requestId, "replyId", reply.getReplyId(),
					"workItemId", linkedWork.get().getTaskId()), null, null);
			resumeJobId = followup.getJobId();
		}
		reply.setResumeJobId(resumeJobId);
		reply = replyRepository.save(reply);
		String savedReplyId=reply.getReplyId();
		workItems.findByRequestId(requestId).ifPresent(w->w.completeFromReply(savedReplyId,now));

		leaveWaitingReply(incident);
		incident.record(now, "REPLY_RECEIVED", user.userId(), "확인 요청 회신: " + input.findings(), "REPLY",
			reply.getReplyId());
		streamHub.publish(incident.getIncidentId(), "check-request.replied",
			Map.of("requestId", requestId, "replyId", reply.getReplyId(), "resumeJobId", String.valueOf(resumeJobId)));
		return new ReplyResult(reply.getReplyId(), requestId, reply.getFindings(), reply.getEvidenceIds(),
			user.userId(), now, resumeJobId, request.getStatus(), request.getVersion());
	}

	/** 담당자가 요청을 맡는다. 이후 내 할 일에 보인다. */
	@Transactional
	public CheckRequestView accept(String requestId, AuthUser user, AcceptInput input) {
		CheckRequest request = loadOwned(requestId, user, input.expectedVersion());
		request.accept();
		Incident incident = loadIncident(request);
		incident.record(Instant.now(clock), "CHECK_REQUEST_ACCEPTED", user.userId(), "확인 요청 수락: " + request.getQuestion(),
			"CHECK_REQUEST", requestId);
		streamHub.publish(incident.getIncidentId(), "check-request.accepted", Map.of("requestId", requestId));
		return view(request);
	}

	/** 담당자가 수락 전에 거절한다. 연결 업무는 거절로 닫고, 회신을 기다리던 분석은 승인 거절과 같이 종료한다. */
	@Transactional
	public CheckRequestView decline(String requestId, AuthUser user, DeclineInput input) {
		CheckRequest request = loadOwned(requestId, user, input.expectedVersion());
		request.decline(input.reason());
		Instant now = Instant.now(clock);
		Incident incident = loadIncident(request);
		workItems.findByRequestId(requestId).ifPresent(w -> w.declineFromRequest(input.reason(), now));
		if (request.getWaitingJobId() != null) {
			jobRepository.findById(request.getWaitingJobId()).filter(job -> job.getStatus() == JobStatus.WAITING_REPLY)
				.ifPresent(job -> jobService.succeed(job, incident, new com.fabcontext.api.common.Ref("REQUEST_DECLINED", requestId)));
		}
		leaveWaitingReply(incident);
		incident.record(now, "CHECK_REQUEST_DECLINED", user.userId(), "확인 요청 거절: " + input.reason(), "CHECK_REQUEST",
			requestId);
		streamHub.publish(incident.getIncidentId(), "check-request.declined", Map.of("requestId", requestId));
		return view(request);
	}

	private CheckRequest loadOwned(String requestId, AuthUser user, Long expectedVersion) {
		CheckRequest request = loadVisible(requestId, user);
		if (!user.userId().equals(request.getOwnerUserId())) {
			throw ApiException.forbidden("NOT_REQUEST_OWNER", "이 요청의 담당자만 처리할 수 있습니다.");
		}
		ApiException.checkVersion("checkRequest", expectedVersion, request.getVersion());
		return request;
	}

	private Incident loadIncident(CheckRequest request) {
		return incidentRepository.findById(request.getIncidentId())
			.orElseThrow(() -> ApiException.notFound("incident", request.getIncidentId()));
	}

	private void leaveWaitingReply(Incident incident) {
		if (incident.getStatus() == IncidentStatus.WAITING_REPLY
				&& !requestRepository.existsByIncidentIdAndStatusIn(incident.getIncidentId(), CheckRequestStatus.AWAITING_REPLY)) {
			incident.setStatus(IncidentStatus.INVESTIGATING);
		}
	}

	/** 요청자 · 담당자 · 사건 공정 권한이 있는 사용자만 조회 가능 */
	private CheckRequest loadVisible(String requestId, AuthUser user) {
		CheckRequest request = requestRepository.findById(requestId)
			.orElseThrow(() -> ApiException.notFound("checkRequest", requestId));
		boolean related = user.userId().equals(request.getOwnerUserId())
				|| user.userId().equals(request.getRequesterUserId()) || user.hasScope(request.getProcessCode());
		if (!related) {
			throw ApiException.forbidden("NOT_REQUEST_PARTICIPANT", "이 요청의 요청자·담당자만 볼 수 있습니다.");
		}
		return request;
	}

	private CheckRequestView view(CheckRequest r) {
		Incident incident = incidentRepository.findById(r.getIncidentId()).orElse(null);
		return new CheckRequestView(r.getRequestId(), r.getIncidentId(), IncidentContext.from(incident),
			r.getTargetProcessCode(), r.getApprovalId(), r.getRequestType(), r.getEquipmentId(), r.getQuestion(), r.getBrief(),
			List.copyOf(r.getCauseIds()), List.copyOf(r.getEvidenceIds()),
			r.getRequesterUserId(), r.getOwnerUserId(), r.getStatus(), r.getVersion(), r.getThreadId(),
			r.getWaitingJobId(), r.getDueAt(), r.getCreatedAt(), r.getRepliedAt(), r.getDeclineReason(),
			replyRepository.findByRequestIdOrderByCreatedAtAsc(r.getRequestId()).stream().map(ReplyView::from).toList());
	}

	@SuppressWarnings("unchecked")
	private static Map<String, Object> castMap(Map<?, ?> m) {
		return (Map<String, Object>) m;
	}

	private String displayId(String incidentId) {
		return incidentRepository.findById(incidentId).map(Incident::getDisplayId).orElse(null);
	}

	/** 외부 Agent가 재개 지점을 맞출 수 있게 job-message의 resume 값(reason · refId · interruptId)도 함께 둔다 */
	private static Map<String, Object> resumeInput(Job waiting, String requestId, String replyId) {
		Map<String, Object> input = new java.util.LinkedHashMap<>();
		input.put("trigger", "REPLY");
		input.put("requestId", requestId);
		input.put("replyId", replyId);
		input.put("reason", "REPLY_RECEIVED");
		input.put("refId", requestId);
		input.put("interruptId", waiting.getInput().get("interruptId"));
		return input;
	}
}
