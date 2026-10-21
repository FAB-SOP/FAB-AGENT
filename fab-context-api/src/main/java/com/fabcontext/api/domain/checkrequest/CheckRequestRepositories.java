package com.fabcontext.api.domain.checkrequest;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

public final class CheckRequestRepositories {

	private CheckRequestRepositories() {
	}

	public interface CheckRequestRepository extends JpaRepository<CheckRequest, String> {

		List<CheckRequest> findByOwnerUserIdOrderByCreatedAtDesc(String ownerUserId);

		List<CheckRequest> findByRequesterUserIdOrderByCreatedAtDesc(String requesterUserId);

		List<CheckRequest> findByIncidentIdOrderByCreatedAtAsc(String incidentId);

		long countByOwnerUserIdAndStatus(String ownerUserId, CheckRequestStatus status);

		boolean existsByIncidentIdAndStatusIn(String incidentId, java.util.Collection<CheckRequestStatus> statuses);

		long countByRequesterUserIdAndStatusIn(String requesterUserId, java.util.Collection<CheckRequestStatus> statuses);
	}

	public interface ReplyRepository extends JpaRepository<Reply, String> {

		List<Reply> findByRequestIdOrderByCreatedAtAsc(String requestId);

		List<Reply> findByIncidentIdOrderByCreatedAtAsc(String incidentId);
	}
}
