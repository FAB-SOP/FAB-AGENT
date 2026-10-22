package com.fabcontext.api.domain.dashboard;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Collectors;

import com.fabcontext.api.auth.AccessPolicy;
import com.fabcontext.api.auth.AuthUser;
import com.fabcontext.api.common.ApiException;
import com.fabcontext.api.domain.approval.ApprovalRepository;
import com.fabcontext.api.domain.approval.ApprovalStatus;
import com.fabcontext.api.domain.checkrequest.CheckRequestRepositories.CheckRequestRepository;
import com.fabcontext.api.domain.checkrequest.CheckRequestStatus;
import com.fabcontext.api.domain.incident.Incident;
import com.fabcontext.api.domain.incident.IncidentRepository;
import com.fabcontext.api.domain.incident.IncidentStatus;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class DashboardService {

	private static final ZoneId KST = ZoneId.of("Asia/Seoul");

	private final IncidentRepository incidentRepository;
	private final ApprovalRepository approvalRepository;
	private final CheckRequestRepository checkRequestRepository;
	private final AccessPolicy accessPolicy;
	private final Clock clock;
	private final com.fabcontext.api.domain.work.WorkItemRepository workItems;

	public record Counts(long total, long active, long pendingApprovals, long openRequests, long sentOpenRequests,
			long analyzing, long analysisFailed) {
	}

	public record TrendBucket(Instant bucketStart, long count, long activeCount) {
	}

	public record ProcessCount(String processCode, long total, long active) {
	}

	public record EquipmentCount(String processCode, String equipmentId, String chamberId, long total, long active) {
	}

	public record Dashboard(String period, String processCode, Instant from, Instant to, String bucket, Counts counts,
			Map<String, Long> byStatus, List<ProcessCount> byProcess, List<EquipmentCount> byEquipment,
			List<TrendBucket> trend, String refreshNote) {
	}

	@Transactional(readOnly = true)
	public Dashboard get(AuthUser user, String periodParam, String processCode) {
		String period = periodParam == null ? "7d" : periodParam;
		Duration span = periodSpan(period);
		List<String> scopes = visibleScopes(user, processCode);
		Instant to = Instant.now(clock);
		Instant from = to.minus(span);
		List<Incident> inScope = incidentRepository.findByProcessCodeInOrderByDetectedAtDesc(scopes);
		List<Incident> inPeriod = inScope.stream().filter(incident -> !incident.getDetectedAt().isBefore(from)).toList();
		Counts counts = counts(user, scopes, to, inScope, inPeriod);
		boolean hourly = span.compareTo(Duration.ofHours(24)) <= 0;
		return new Dashboard(period, processCode, from, to, hourly ? "HOUR" : "DAY", counts, statusCounts(inScope),
			processCounts(scopes, inScope, inPeriod), equipmentCounts(inScope, from), trend(hourly, from, to, inPeriod),
			"전역 실시간 스트림은 계약에 없습니다. 현황 갱신은 이 API를 다시 조회하세요.");
	}

	private static Duration periodSpan(String period) {
		return switch (period) {
			case "24h" -> Duration.ofHours(24);
			case "7d" -> Duration.ofDays(7);
			case "30d" -> Duration.ofDays(30);
			default -> throw ApiException.badRequest("INVALID_PERIOD", "period는 24h · 7d · 30d 중 하나입니다.");
		};
	}

	/** 공정을 지정하면 그 공정 권한을 확인하고 그 공정만, 아니면 사용자의 담당 공정 전체 */
	private List<String> visibleScopes(AuthUser user, String processCode) {
		if (processCode != null && !processCode.isBlank()) {
			accessPolicy.requireScope(user, processCode);
			return List.of(processCode);
		}
		return user.processScopes();
	}

	private Counts counts(AuthUser user, List<String> scopes, Instant to, List<Incident> inScope, List<Incident> inPeriod) {
		long pendingApprovals = pendingApprovals(user, scopes, to);
		return new Counts(inPeriod.size(),
			inScope.stream().filter(Incident::isActive).count(),
			pendingApprovals,
			checkRequestRepository.countByOwnerUserIdAndStatus(user.userId(), CheckRequestStatus.OPEN),
			checkRequestRepository.countByRequesterUserIdAndStatus(user.userId(), CheckRequestStatus.OPEN),
			inScope.stream()
				.filter(incident -> "QUEUED".equals(incident.getLatestJobStatus()) || "RUNNING".equals(incident.getLatestJobStatus()))
				.count(),
			inScope.stream().filter(incident -> "FAILED".equals(incident.getLatestJobStatus())).count());
	}

	/** 만료되지 않은 승인 대기 제안 + 내가 결재할 관리자 승인 대기 업무 */
	private long pendingApprovals(AuthUser user, List<String> scopes, Instant to) {
		long pendingProposals = approvalRepository.findByProcessCodeInOrderByCreatedAtDesc(scopes).stream()
			.filter(approval -> approval.getStatus() == ApprovalStatus.PENDING
				&& (approval.getExpiresAt() == null || approval.getExpiresAt().isAfter(to)))
			.count();
		long waitingManager = workItems.findAll().stream()
			.filter(workItem -> scopes.contains(workItem.getProcessCode())
				&& user.userId().equals(workItem.getApproverUserId())
				&& workItem.getStatus() == com.fabcontext.api.domain.work.WorkItem.Status.WAITING_MANAGER)
			.count();
		return pendingProposals + waitingManager;
	}

	private static Map<String, Long> statusCounts(List<Incident> inScope) {
		Map<String, Long> byStatus = new LinkedHashMap<>();
		for (IncidentStatus status : IncidentStatus.values()) {
			byStatus.put(status.name(), inScope.stream().filter(incident -> incident.getStatus() == status).count());
		}
		return byStatus;
	}

	private static List<ProcessCount> processCounts(List<String> scopes, List<Incident> inScope, List<Incident> inPeriod) {
		return scopes.stream()
			.map(process -> new ProcessCount(process,
				inPeriod.stream().filter(incident -> process.equals(incident.getProcessCode())).count(),
				inScope.stream().filter(incident -> process.equals(incident.getProcessCode()) && incident.isActive()).count()))
			.toList();
	}

	private static List<EquipmentCount> equipmentCounts(List<Incident> inScope, Instant from) {
		Map<String, List<Incident>> byEquipment = inScope.stream()
			.collect(Collectors.groupingBy(
				incident -> incident.getProcessCode() + "|" + incident.getEquipmentId() + "|" + incident.getChamberId(),
				TreeMap::new, Collectors.toList()));
		return byEquipment.values().stream().map(incidents -> {
			Incident first = incidents.getFirst();
			return new EquipmentCount(first.getProcessCode(), first.getEquipmentId(), first.getChamberId(),
				incidents.stream().filter(incident -> !incident.getDetectedAt().isBefore(from)).count(),
				incidents.stream().filter(Incident::isActive).count());
		}).toList();
	}

	/** 24시간 이하는 시간 단위, 그 밖은 KST 날짜 단위 구간별 사건 수 */
	private static List<TrendBucket> trend(boolean hourly, Instant from, Instant to, List<Incident> inPeriod) {
		ChronoUnit unit = hourly ? ChronoUnit.HOURS : ChronoUnit.DAYS;
		Instant start = hourly ? from.truncatedTo(ChronoUnit.HOURS)
			: from.atZone(KST).truncatedTo(ChronoUnit.DAYS).toInstant();
		List<TrendBucket> trend = new ArrayList<>();
		for (Instant bucketStart = start; bucketStart.isBefore(to); bucketStart = bucketStart.plus(1, unit)) {
			Instant bucketEnd = bucketStart.plus(1, unit);
			Instant currentStart = bucketStart;
			List<Incident> bucket = inPeriod.stream()
				.filter(incident -> !incident.getDetectedAt().isBefore(currentStart) && incident.getDetectedAt().isBefore(bucketEnd))
				.toList();
			trend.add(new TrendBucket(currentStart, bucket.size(), bucket.stream().filter(Incident::isActive).count()));
		}
		return trend;
	}
}
