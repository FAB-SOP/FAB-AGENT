package com.fabcontext.api.domain.action;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import com.fabcontext.api.auth.AuthUser;
import com.fabcontext.api.common.ApiException;
import com.fabcontext.api.common.Ids;
import com.fabcontext.api.domain.action.ActionDtos.ActionInput;
import com.fabcontext.api.domain.action.ActionDtos.ActionPage;
import com.fabcontext.api.domain.action.ActionDtos.ActionView;
import com.fabcontext.api.domain.action.ActionDtos.VerificationInput;
import com.fabcontext.api.domain.incident.Incident;
import com.fabcontext.api.domain.incident.IncidentService;
import com.fabcontext.api.domain.job.IncidentStreamHub;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class ActionService {

	private static final Duration CLOCK_SKEW = Duration.ofMinutes(5);

	private final IncidentActionRepository repository;
	private final IncidentService incidentService;
	private final IncidentStreamHub streamHub;
	private final Clock clock;

	@Transactional(readOnly = true)
	public ActionPage list(String incidentId, AuthUser user) {
		incidentService.loadForUser(incidentId, user);
		return new ActionPage(incidentId,
			repository.findByIncidentIdOrderByPerformedAtAsc(incidentId).stream().map(ActionView::from).toList());
	}

	@Transactional
	public ActionView record(String incidentId, AuthUser user, ActionInput input) {
		Incident incident = incidentService.loadForUser(incidentId, user);
		incident.requireNotClosed();
		incident.checkVersion(input.expectedIncidentVersion());
		if (!Boolean.TRUE.equals(input.confirmedByUser())) {
			throw ApiException.unprocessable("USER_CONFIRMATION_REQUIRED",
				"실제로 수행한 조치임을 confirmedByUser=true로 확인해야 합니다.");
		}
		IncidentAction action = save(incident, input.actionType(), input.description(), input.performedAt(),
			user.userId(), input.evidenceIds(), "USER", null);
		return ActionView.from(action);
	}

	/** 승인된 RECORD_ACTION 제안의 저장된 payload로 조치를 기록한다. */
	public IncidentAction recordFromApproval(Incident incident, String approvalId, Map<String, Object> payload,
			List<String> evidenceIds, String approverId) {
		return save(incident, (String) payload.get("actionType"), (String) payload.get("description"),
			Instant.parse(payload.get("performedAt").toString()),
			payload.getOrDefault("performedBy", approverId).toString(), evidenceIds, "APPROVAL", approvalId);
	}

	@Transactional
	public ActionView verify(String actionId, AuthUser user, VerificationInput input) {
		IncidentAction action = repository.findById(actionId)
			.orElseThrow(() -> ApiException.notFound("action", actionId));
		Incident incident = incidentService.loadForUser(action.getIncidentId(), user);
		if (!Boolean.TRUE.equals(input.confirmedByUser())) {
			throw ApiException.unprocessable("USER_CONFIRMATION_REQUIRED", "후속 데이터를 직접 검토했음을 confirmedByUser=true로 확인해야 합니다.");
		}
		if (input.outcome() == VerificationStatus.PENDING) {
			throw ApiException.unprocessable("INVALID_OUTCOME", "outcome은 IMPROVED · UNCHANGED · INCONCLUSIVE 중 하나입니다.");
		}
		ApiException.checkVersion("action", input.expectedVersion(), action.getVersion());
		Instant now = Instant.now(clock);
		action.verify(new ActionVerification(Ids.next("ver"), input.outcome(), input.rationale(),
			input.evidenceIds() == null ? List.of() : input.evidenceIds(), user.userId(), now));
		// 효과 기록만으로 사건을 자동 종료하지 않는다.
		incident.record(now, "ACTION_VERIFIED", user.userId(),
			"조치 효과 확인(" + input.outcome() + "): " + input.rationale(), "ACTION", actionId);
		streamHub.publish(incident.getIncidentId(), "action.verified",
			Map.of("actionId", actionId, "verificationStatus", input.outcome().name()));
		return ActionView.from(action);
	}

	private IncidentAction save(Incident incident, String actionType, String description, Instant performedAt,
			String performedBy, List<String> evidenceIds, String source, String approvalId) {
		Instant now = Instant.now(clock);
		if (performedAt.isAfter(now.plus(CLOCK_SKEW))) {
			throw ApiException.unprocessable("PERFORMED_AT_IN_FUTURE", "performedAt은 미래일 수 없습니다. 실제 수행한 조치만 기록합니다.");
		}
		IncidentAction action = IncidentAction.builder()
			.actionId(Ids.next("act"))
			.incidentId(incident.getIncidentId())
			.processCode(incident.getProcessCode())
			.actionType(actionType)
			.description(description)
			.performedAt(performedAt)
			.performedBy(performedBy)
			.evidenceIds(evidenceIds)
			.source(source)
			.approvalId(approvalId)
			.createdAt(now)
			.build();
		action = repository.save(action);
		incident.record(now, "ACTION_RECORDED", performedBy, "조치 기록(" + actionType + "): " + description, "ACTION",
			action.getActionId());
		streamHub.publish(incident.getIncidentId(), "action.recorded", Map.of("actionId", action.getActionId()));
		return action;
	}
}
