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
		Duration span = switch (period) {
			case "24h" -> Duration.ofHours(24);
			case "7d" -> Duration.ofDays(7);
			case "30d" -> Duration.ofDays(30);
			default -> throw ApiException.badRequest("INVALID_PERIOD", "period는 24h · 7d · 30d 중 하나입니다.");
		};
		List<String> scopes = user.processScopes();
		if (processCode != null && !processCode.isBlank()) {
			accessPolicy.requireScope(user, processCode);
			scopes = List.of(processCode);
		}
		Instant to = Instant.now(clock);
		Instant from = to.minus(span);
		List<Incident> inScope = incidentRepository.findByProcessCodeInOrderByDetectedAtDesc(scopes);
		List<Incident> inPeriod = inScope.stream().filter(i -> !i.getDetectedAt().isBefore(from)).toList();

		long pendingApprovals = approvalRepository.findByProcessCodeInOrderByCreatedAtDesc(scopes).stream()
			.filter(a -> a.getStatus() == ApprovalStatus.PENDING && (a.getExpiresAt() == null || a.getExpiresAt().isAfter(to)))
			.count();
		Counts counts = new Counts(inPeriod.size(),
			inScope.stream().filter(Incident::isActive).count(),
			pendingApprovals,
			checkRequestRepository.countByOwnerUserIdAndStatus(user.userId(), CheckRequestStatus.OPEN),
			checkRequestRepository.countByRequesterUserIdAndStatus(user.userId(), CheckRequestStatus.OPEN),
			inScope.stream().filter(i -> "QUEUED".equals(i.getLatestJobStatus()) || "RUNNING".equals(i.getLatestJobStatus()))
				.count(),
			inScope.stream().filter(i -> "FAILED".equals(i.getLatestJobStatus())).count());

		Map<String, Long> byStatus = new LinkedHashMap<>();
		for (IncidentStatus s : IncidentStatus.values()) {
			byStatus.put(s.name(), inScope.stream().filter(i -> i.getStatus() == s).count());
		}
		List<ProcessCount> byProcess = scopes.stream()
			.map(p -> new ProcessCount(p, inPeriod.stream().filter(i -> p.equals(i.getProcessCode())).count(),
				inScope.stream().filter(i -> p.equals(i.getProcessCode()) && i.isActive()).count()))
			.toList();
		Map<String, List<Incident>> byEquipmentMap = inScope.stream()
			.collect(Collectors.groupingBy(i -> i.getProcessCode() + "|" + i.getEquipmentId() + "|" + i.getChamberId(),
				TreeMap::new, Collectors.toList()));
		List<EquipmentCount> byEquipment = byEquipmentMap.values().stream().map(list -> {
			Incident first = list.getFirst();
			return new EquipmentCount(first.getProcessCode(), first.getEquipmentId(), first.getChamberId(),
				list.stream().filter(i -> !i.getDetectedAt().isBefore(from)).count(),
				list.stream().filter(Incident::isActive).count());
		}).toList();

		boolean hourly = span.compareTo(Duration.ofHours(24)) <= 0;
		ChronoUnit unit = hourly ? ChronoUnit.HOURS : ChronoUnit.DAYS;
		Instant start = hourly ? from.truncatedTo(ChronoUnit.HOURS)
			: from.atZone(KST).truncatedTo(ChronoUnit.DAYS).toInstant();
		List<TrendBucket> trend = new ArrayList<>();
		for (Instant b = start; b.isBefore(to); b = b.plus(1, unit)) {
			Instant bStart = b;
			Instant bEnd = b.plus(1, unit);
			List<Incident> bucket = inPeriod.stream()
				.filter(i -> !i.getDetectedAt().isBefore(bStart) && i.getDetectedAt().isBefore(bEnd))
				.toList();
			trend.add(new TrendBucket(bStart, bucket.size(), bucket.stream().filter(Incident::isActive).count()));
		}
		return new Dashboard(period, processCode, from, to, hourly ? "HOUR" : "DAY", counts, byStatus, byProcess,
			byEquipment, trend, "전역 실시간 스트림은 계약에 없습니다. 현황 갱신은 이 API를 다시 조회하세요.");
	}
}
