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
import com.fabcontext.api.domain.checkrequest.CheckRequestDtos.CheckRequestSummary;
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
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class CheckRequestService {

	private final com.fabcontext.api.domain.notification.NotificationEvents notificationEvents;
	private final CheckRequestRepository requestRepository;
	private final ReplyRepository replyRepository;
	private final IncidentRepository incidentRepository;
	private final jakarta.persistence.EntityManager entityManager;
	private final IncidentStreamHub streamHub;
	private final Clock clock;
	private final com.fabcontext.api.domain.work.WorkItemRepository workItems;

	@Transactional(readOnly=true)
	public List<CheckRequestSummary> acceptedInbox(AuthUser user) {
		return requestRepository.findByOwnerUserIdOrderByCreatedAtDesc(user.userId()).stream()
			.filter(r->r.getStatus()==CheckRequestStatus.ACCEPTED)
			.map(r->CheckRequestSummary.from(r,displayId(r.getIncidentId()),user.userId())).toList();
	}

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
		notificationEvents.checkRequestCreated(request);
		return request;
	}

	@Transactional(readOnly = true)
	public PageResponse<CheckRequestSummary> list(AuthUser user, String box, String status, String incidentId,
			String cursor, Integer size) {
		String mode = box == null ? "received" : box.toLowerCase();
		java.util.Set<String> statuses = new java.util.HashSet<>();
		if (status != null) for (String value : status.split(",", -1)) {
			try { statuses.add(CheckRequestStatus.valueOf(value.trim().toUpperCase(java.util.Locale.ROOT)).name()); }
			catch (IllegalArgumentException ex) { throw ApiException.badRequest("INVALID_STATUS", "요청 상태가 올바르지 않습니다."); }
		}
		List<CheckRequest> source = switch (mode) {
			case "received" -> requestRepository.findByOwnerUserIdOrderByCreatedAtDesc(user.userId());
			case "sent" -> requestRepository.findByRequesterUserIdOrderByCreatedAtDesc(user.userId());
			default -> throw ApiException.badRequest("INVALID_BOX", "box는 received 또는 sent입니다.");
		};
		List<CheckRequest> filtered = source.stream()
			.filter(r -> statuses.isEmpty() || statuses.contains(r.getStatus().name()))
			.filter(r -> incidentId == null || incidentId.equals(r.getIncidentId()))
			.toList();
		return Cursors.page(filtered, cursor, size, r -> CheckRequestSummary.from(r, displayId(r.getIncidentId()), user.userId()));
	}

	@Transactional(readOnly = true)
	public List<CheckRequestView> forIncident(String id, AuthUser user) {
		Incident incident=incidentRepository.findById(id).orElseThrow(()->ApiException.notFound("incident",id));
		if(!user.hasScope(incident.getProcessCode())) throw ApiException.forbidden("PROCESS_SCOPE_REQUIRED","사건 공정의 조회 권한이 필요합니다.");
		return requestRepository.findByIncidentIdOrderByCreatedAtAsc(id).stream().map(r -> view(r,user.userId())).toList();
	}

	@Transactional(readOnly = true)
	public CheckRequestView get(String requestId, AuthUser user) {
		CheckRequest request = loadVisible(requestId, user);
		return view(request,user.userId());
	}

	@Transactional
	public ReplyResult reply(String requestId, AuthUser user, ReplyInput input) {
		CheckRequest request = loadVisible(requestId, user);
		entityManager.refresh(request, jakarta.persistence.LockModeType.PESSIMISTIC_WRITE);
		requireReplyable(request, user, input);
		Instant now = Instant.now(clock);
		request.markReplied(now);
		Incident incident = incidentRepository.findById(request.getIncidentId())
			.orElseThrow(() -> ApiException.notFound("incident", request.getIncidentId()));
		Reply reply = newReply(requestId, incident, user, input, now);

		incident.requireNotClosed();
		validateEvidence(request, input.evidenceIds());
		String resumeJobId = null; // 회신 저장은 분석을 실행하지 않는다.
		reply = replyRepository.save(reply);
		String savedReplyId = reply.getReplyId();
		workItems.findByRequestId(requestId).ifPresent(workItem -> workItem.completeFromReply(savedReplyId, now));
		notificationEvents.checkRequestReplied(request, reply);

		if (incident.getStatus() == IncidentStatus.WAITING_REPLY
				&& !hasPending(incident.getIncidentId())) {
			incident.setStatus(IncidentStatus.INVESTIGATING);
		}
		incident.record(now, "REPLY_RECEIVED", user.userId(), "확인 요청 회신: " + input.findings(), "REPLY",
			reply.getReplyId());
		streamHub.publish(incident.getIncidentId(), "check-request.replied",
			Map.of("requestId", requestId, "replyId", reply.getReplyId()));
		return new ReplyResult(reply.getReplyId(), requestId, reply.getFindings(), reply.getEvidenceIds(),
			user.userId(), now, resumeJobId, request.getStatus(), request.getVersion());
	}

	@Transactional
	public CheckRequestView decide(String requestId, AuthUser user, CheckRequestDtos.DecisionInput input) {
		CheckRequest request=loadVisible(requestId,user);
		if (!user.userId().equals(request.getOwnerUserId())) throw ApiException.forbidden("NOT_REQUEST_OWNER","지정 수신자만 결정할 수 있습니다.");
		entityManager.refresh(request, jakarta.persistence.LockModeType.PESSIMISTIC_WRITE);
		Incident incident=incidentRepository.findById(request.getIncidentId()).orElseThrow();
		incident.requireNotClosed();
		ApiException.checkVersion("checkRequest",input.expectedVersion(),request.getVersion());
		Instant now=Instant.now(clock);
		boolean accepted="ACCEPT".equals(input.decision());
		request.decide(accepted,input.comment(),now);
		notificationEvents.checkRequestDecided(request,user.userId(),accepted);
		if (!accepted) workItems.findByRequestId(requestId).ifPresent(w->w.rejectFromRequest(input.comment(),now));
		if (!accepted && incident.getStatus()==IncidentStatus.WAITING_REPLY && !hasPending(incident.getIncidentId())) incident.setStatus(IncidentStatus.INVESTIGATING);
		incident.record(now,accepted?"REQUEST_ACCEPTED":"REQUEST_REJECTED",user.userId(),accepted?"확인 요청 수락":"확인 요청 거절: "+input.comment(),"CHECK_REQUEST",requestId);
		streamHub.publish(incident.getIncidentId(),"check-request.decided",Map.of("requestId",requestId,"status",request.getStatus()));
		return view(request,user.userId());
	}

	private boolean hasPending(String incidentId) {
		return requestRepository.existsByIncidentIdAndStatusIn(incidentId,List.of(CheckRequestStatus.OPEN,CheckRequestStatus.ACCEPTED));
	}
	private void validateEvidence(CheckRequest request, List<String> ids) {
		if(ids==null || ids.isEmpty()) return;
		java.util.Set<String> known=new java.util.HashSet<>(request.getEvidenceIds());
		// 수신자가 넘겨받은 근거만 재첨부할 수 있다. 새 근거는 별도 등록 경로가 필요하다.
		if(!known.containsAll(ids)) throw ApiException.unprocessable("INVALID_EVIDENCE","요청에 연결되어 전달된 근거만 첨부할 수 있습니다.");
	}

	/** 회신 전 검사 — 담당자 본인 → 직접 확인 표시 → 버전 순서 */
	private static void requireReplyable(CheckRequest request, AuthUser user, ReplyInput input) {
		if (!user.userId().equals(request.getOwnerUserId())) {
			throw ApiException.forbidden("NOT_REQUEST_OWNER", "이 요청의 담당자만 회신할 수 있습니다.");
		}
		if (!Boolean.TRUE.equals(input.confirmedByUser())) {
			throw ApiException.unprocessable("USER_CONFIRMATION_REQUIRED", "담당자가 직접 확인했음을 confirmedByUser=true로 표시해야 합니다.");
		}
		ApiException.checkVersion("checkRequest", input.expectedVersion(), request.getVersion());
	}

	private static Reply newReply(String requestId, Incident incident, AuthUser user, ReplyInput input, Instant now) {
		return Reply.builder()
			.replyId(Ids.next("rpl"))
			.requestId(requestId)
			.incidentId(incident.getIncidentId())
			.findings(input.findings())
			.evidenceIds(input.evidenceIds() == null ? List.of() : input.evidenceIds())
			.authorUserId(user.userId())
			.confirmedByUser(true)
			.createdAt(now)
			.build();
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

	private CheckRequestView view(CheckRequest r, String userId) {
		Incident incident = incidentRepository.findById(r.getIncidentId()).orElse(null);
		return new CheckRequestView(r.getRequestId(), r.getIncidentId(), IncidentContext.from(incident),
			r.getTargetProcessCode(), r.getApprovalId(), r.getRequestType(), r.getEquipmentId(), r.getQuestion(), r.getBrief(),
			List.copyOf(r.getCauseIds()), List.copyOf(r.getEvidenceIds()),
			r.getRequesterUserId(), r.getOwnerUserId(), r.getStatus(), r.getVersion(), r.getThreadId(),
			r.getWaitingJobId(), r.getDueAt(), r.getCreatedAt(), r.getRepliedAt(),
			replyRepository.findByRequestIdOrderByCreatedAtAsc(r.getRequestId()).stream().map(ReplyView::from).toList(), r.getDecisionAt(), r.getDecisionComment(), CheckRequestDtos.allowed(r,userId));
	}

	@SuppressWarnings("unchecked")
	private static Map<String, Object> castMap(Map<?, ?> m) {
		return (Map<String, Object>) m;
	}

	private String displayId(String incidentId) {
		return incidentRepository.findById(incidentId).map(Incident::getDisplayId).orElse(null);
	}

}
