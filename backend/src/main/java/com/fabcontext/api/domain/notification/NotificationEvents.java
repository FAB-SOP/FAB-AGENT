package com.fabcontext.api.domain.notification;

import java.util.List;

import com.fabcontext.api.auth.AuthUser;
import com.fabcontext.api.auth.Role;
import com.fabcontext.api.auth.UserAccount;
import com.fabcontext.api.auth.UserAccountRepository;
import com.fabcontext.api.common.Ref;
import com.fabcontext.api.domain.checkrequest.CheckRequest;
import com.fabcontext.api.domain.checkrequest.Reply;
import com.fabcontext.api.domain.incident.Incident;
import com.fabcontext.api.domain.incident.IncidentRepository;
import com.fabcontext.api.domain.job.Job;
import com.fabcontext.api.domain.job.JobKind;
import com.fabcontext.api.domain.job.JobStatus;
import com.fabcontext.api.domain.work.WorkItem;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * 업무 이벤트 → 알림 어댑터. 수신자는 리소스에 저장된 실제 관계(담당자 · 요청자 · 지정 승인자 · 작업 요청자)로만 정한다.
 * 업무 서비스는 자기 트랜잭션 안에서 해당 메서드 하나만 부른다 — 저장은 커밋 직전, 전달은 커밋 후({@link NotificationService}).
 * 여기서는 업무 상태를 바꾸거나 분석 · Agent를 실행하지 않는다.
 * <p>
 * 확장 지점: 보고서 요약(SUMMARY) 완료 · 실패 알림은 이번 범위가 아니다. 추가할 때 {@link NotificationType}에 종류를 더하고
 * 요약 결과 반영 트랜잭션(SUMMARY_READY 검증을 통과한 경우만)에서 이 클래스의 새 메서드를 부른다. 수신자는 보고서 확정자로 할지 합의가 필요하다.
 */
@Component
@RequiredArgsConstructor
public class NotificationEvents {

	private final NotificationService notifications;
	private final UserAccountRepository users;
	private final IncidentRepository incidents;

	/**
	 * 새 사건 → 담당자. 순서: 설비 담당 매핑 → 공정 담당자 → (둘 다 없으면) 그 공정 범위의 관리자.
	 * 설비 담당 매핑은 현재 사용자 모델에 없어 건너뛴다(합의 후 추가). 공정 담당자는 사건을 운영할 수 있는 사용자
	 * ({@link AuthUser#investigationProcessScopes()} — AccessPolicy.requireInvestigationScope와 같은 기준)이며 관리자는 제외한다.
	 */
	public void incidentCreated(Incident incident) {
		notifications.publish(new NotificationDraft(NotificationType.INCIDENT_CREATED, incident.getIncidentId(),
			incident.getIncidentId(), null, incident.getIncidentId(), incident.getDisplayId(), null),
			incidentAssignees(incident.getProcessCode()));
	}

	/** 새 업무 · 확인 요청 → 지정 수신자(ownerUserId) */
	public void checkRequestCreated(CheckRequest request) {
		notifications.publish(new NotificationDraft(NotificationType.CHECK_REQUEST_RECEIVED, request.getRequestId(),
			request.getRequestId(), null, request.getIncidentId(), displayId(request.getIncidentId()),
			request.getRequesterUserId()), List.of(nullToEmpty(request.getOwnerUserId())));
	}

	/**
	 * 요청 수락 · 거절 → 요청자. CheckRequestService.decide의 결정 트랜잭션에서 호출한다.
	 * 결정마다 한 번(sourceKey = requestId)이며 거절 사유는 알림에 담지 않는다.
	 */
	public void checkRequestDecided(CheckRequest request, String deciderUserId, boolean accepted) {
		notifications.publish(new NotificationDraft(
			accepted ? NotificationType.CHECK_REQUEST_ACCEPTED : NotificationType.CHECK_REQUEST_REJECTED,
			request.getRequestId(), request.getRequestId(), null, request.getIncidentId(),
			displayId(request.getIncidentId()), deciderUserId), List.of(nullToEmpty(request.getRequesterUserId())));
	}

	/** 결과 회신 → 요청자. 회신 도착을 알릴 뿐 분석을 시작하지 않는다 */
	public void checkRequestReplied(CheckRequest request, Reply reply) {
		notifications.publish(new NotificationDraft(NotificationType.CHECK_REQUEST_REPLIED, reply.getReplyId(),
			reply.getReplyId(), new Ref("CHECK_REQUEST", request.getRequestId()), request.getIncidentId(),
			displayId(request.getIncidentId()), reply.getAuthorUserId()), List.of(nullToEmpty(request.getRequesterUserId())));
	}

	/** 상급자 승인 요청 → 지정 승인자. WAITING_MANAGER가 된 업무만 */
	public void workApprovalRequested(WorkItem work) {
		if (work.getStatus() != WorkItem.Status.WAITING_MANAGER) {
			return;
		}
		notifications.publish(new NotificationDraft(NotificationType.WORK_APPROVAL_REQUESTED, work.getTaskId(),
			work.getTaskId(), new Ref("INCIDENT", work.getIncidentId()), work.getIncidentId(),
			displayId(work.getIncidentId()), work.getRequestedBy()), List.of(nullToEmpty(work.getApproverUserId())));
	}

	/** 승인 · 반려 → 승인 요청자. APPROVED · REJECTED가 된 업무만(제안 거절 DECLINED는 대상 아님) */
	public void workDecided(WorkItem work, String deciderUserId) {
		NotificationType type = switch (work.getStatus()) {
			case APPROVED -> NotificationType.WORK_APPROVED;
			case REJECTED -> NotificationType.WORK_REJECTED;
			default -> null;
		};
		if (type == null) {
			return;
		}
		notifications.publish(new NotificationDraft(type, work.getTaskId(), work.getTaskId(),
			new Ref("INCIDENT", work.getIncidentId()), work.getIncidentId(), displayId(work.getIncidentId()),
			deciderUserId), List.of(nullToEmpty(work.getRequestedBy())));
	}

	/**
	 * 분석 완료 · 실패 → 작업 요청자. 보고서(REPORT) 작업은 제외한다. 요청자가 등록 사용자이고 지금도 사건 공정을 볼 수 있을 때만
	 * 보낸다(BASIC의 svc_simulator · agent는 사람이 아니므로 대상 없음).
	 */
	public void analysisFinished(Job job, Incident incident) {
		if (job.getKind() == JobKind.REPORT) {
			return;
		}
		// 승인 거절/요청 발송만 끝난 작업을 분석 결과 완료로 알리지 않는다.
		if(job.getStatus()==JobStatus.SUCCEEDED && !"ANALYSIS_RESULT".equals(job.getResultRefType())) return;
		NotificationType type = job.getStatus() == JobStatus.SUCCEEDED ? NotificationType.ANALYSIS_SUCCEEDED
			: job.getStatus() == JobStatus.FAILED ? NotificationType.ANALYSIS_FAILED : null;
		if (type == null || job.getRequestedBy() == null) {
			return;
		}
		boolean canView = users.findById(job.getRequestedBy()).map(AuthUser::from)
			.filter(u -> u.hasScope(incident.getProcessCode())).isPresent();
		if (!canView) {
			return;
		}
		notifications.publish(new NotificationDraft(type, job.getJobId(), job.getJobId(),
			new Ref("INCIDENT", incident.getIncidentId()), incident.getIncidentId(), incident.getDisplayId(), null),
			List.of(job.getRequestedBy()));
	}

	List<String> incidentAssignees(String processCode) {
		List<UserAccount> all = users.findAll();
		List<String> owners = all.stream()
			.filter(u -> u.getRole() != Role.MANAGER)
			.filter(u -> AuthUser.from(u).investigationProcessScopes().contains(processCode))
			.map(UserAccount::getUserId).sorted().toList();
		if (!owners.isEmpty()) {
			return owners;
		}
		return all.stream()
			.filter(u -> u.getRole() == Role.MANAGER && u.getProcessScopes().contains(processCode))
			.map(UserAccount::getUserId).sorted().toList();
	}

	private String displayId(String incidentId) {
		return incidentId == null ? null : incidents.findById(incidentId).map(Incident::getDisplayId).orElse(null);
	}

	private static String nullToEmpty(String value) {
		return value == null ? "" : value;
	}
}
