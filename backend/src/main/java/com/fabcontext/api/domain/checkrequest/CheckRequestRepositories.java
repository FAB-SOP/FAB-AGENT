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

		long countByRequesterUserIdAndStatus(String requesterUserId, CheckRequestStatus status);

		boolean existsByIncidentIdAndStatus(String incidentId, CheckRequestStatus status);
	}

	public interface ReplyRepository extends JpaRepository<Reply, String> {

		List<Reply> findByRequestIdOrderByCreatedAtAsc(String requestId);

		List<Reply> findByIncidentIdOrderByCreatedAtAsc(String incidentId);
	}
}
