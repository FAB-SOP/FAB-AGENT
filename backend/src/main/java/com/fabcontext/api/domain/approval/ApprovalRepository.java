package com.fabcontext.api.domain.approval;

import java.util.Collection;
import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

public interface ApprovalRepository extends JpaRepository<Approval, String> {

	java.util.Optional<Approval> findByProposalKey(String proposalKey);


	List<Approval> findByProcessCodeInOrderByCreatedAtDesc(Collection<String> processCodes);

	long countByProcessCodeInAndStatus(Collection<String> processCodes, ApprovalStatus status);

	List<Approval> findByIncidentIdAndStatus(String incidentId, ApprovalStatus status);
}
