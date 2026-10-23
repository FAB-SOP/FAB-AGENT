package com.fabcontext.api.domain.incident;

import java.time.Clock;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import com.fabcontext.api.auth.AccessPolicy;
import com.fabcontext.api.auth.AuthUser;
import com.fabcontext.api.common.ApiException;
import com.fabcontext.api.common.Cursors;
import com.fabcontext.api.common.PageResponse;
import com.fabcontext.api.domain.incident.IncidentDtos.IncidentDetail;
import com.fabcontext.api.domain.incident.IncidentDtos.IncidentSummary;
import com.fabcontext.api.domain.incident.IncidentDtos.IncidentTransition;
import com.fabcontext.api.common.stream.IncidentStreamHub;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class IncidentService {

	private final IncidentRepository incidentRepository;
	private final ActiveJobLookup activeJobs;
	private final AccessPolicy accessPolicy;
	private final IncidentStreamHub streamHub;
	private final Clock clock;

	/** 존재 확인 + 공정 범위 권한 확인. 호출자의 트랜잭션에 참여한다. */
	@Transactional
	public Incident loadForUser(String incidentId, AuthUser user) {
		Incident incident = incidentRepository.findById(incidentId)
			.orElseThrow(() -> ApiException.notFound("incident", incidentId));
		accessPolicy.requireScope(user, incident.getProcessCode());
		return incident;
	}

	/** 조회 범위와 사건 운영 권한을 구분한다. */
	@Transactional
	public Incident loadForWrite(String incidentId, AuthUser user) {
		Incident incident = loadForUser(incidentId, user);
		accessPolicy.requireInvestigationScope(user, incident.getProcessCode());
		return incident;
	}

	@Transactional(readOnly = true)
	public PageResponse<IncidentSummary> list(AuthUser user, String q, String status, String jobStatus,
			String processCode, String cursor, Integer size) {
		List<String> scopes = user.processScopes();
		if (processCode != null && !processCode.isBlank()) {
			accessPolicy.requireScope(user, processCode);
			scopes = List.of(processCode);
		}
		Set<String> statuses = csv(status);
		Set<String> jobStatuses = csv(jobStatus);
		String query = q == null ? null : q.trim().toLowerCase(Locale.ROOT);
		List<Incident> filtered = incidentRepository.findByProcessCodeInOrderByDetectedAtDesc(scopes).stream()
			.filter(i -> statuses.isEmpty() || statuses.contains(i.getStatus().name()))
			.filter(i -> jobStatuses.isEmpty() || jobStatuses.contains(i.getLatestJobStatus()))
			.filter(i -> query == null || query.isEmpty() || matches(i, query))
			.toList();
		return Cursors.page(filtered, cursor, size, IncidentSummary::from);
	}

	@Transactional(readOnly = true)
	public IncidentDetail detail(String incidentId, AuthUser user) {
		Incident incident = loadForUser(incidentId, user);
		return IncidentDetail.from(incident, activeJobs.find(incident.getActiveJobId()));
	}

	@Transactional
	public IncidentDetail transition(String incidentId, AuthUser user, IncidentTransition body) {
		Incident incident = loadForUser(incidentId, user);
		accessPolicy.requireManager(user);
		incident.checkVersion(body.expectedVersion());
		// 사건 종료는 보고서 확정으로만 일어난다(헌법 원칙 VI 3.0.0) — 관리자의 수동 보류 · 보류 해제 · 종료는 없다.
		// 경로는 호환을 위해 남겨 두고 권한 · 버전 검사 뒤 항상 거부한다
		throw ApiException.conflict("TRANSITION_REMOVED", "사건 상태는 바꿀 수 없습니다. 사건은 보고서 확정으로만 종료됩니다.",
			Map.of("currentStatus", incident.getStatus().name(), "targetStatus", body.targetStatus().name()));
	}

	private static boolean matches(Incident i, String q) {
		return contains(i.getDisplayId(), q) || contains(i.getIncidentId(), q) || contains(i.getTitle(), q)
				|| contains(i.getEquipmentId(), q) || contains(i.getChamberId(), q) || contains(i.getLotId(), q)
				|| contains(i.getAnomalyType(), q) || i.getWaferIds().stream().anyMatch(w -> contains(w, q));
	}

	private static boolean contains(String value, String q) {
		return value != null && value.toLowerCase(Locale.ROOT).contains(q);
	}

	static Set<String> csv(String value) {
		if (value == null || value.isBlank()) {
			return Set.of();
		}
		return Arrays.stream(value.split(",")).map(String::trim).filter(s -> !s.isEmpty())
			.map(s -> s.toUpperCase(Locale.ROOT)).collect(Collectors.toSet());
	}
}
