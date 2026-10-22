package com.fabcontext.api.domain.action;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

public interface IncidentActionRepository extends JpaRepository<IncidentAction, String> {

	List<IncidentAction> findByIncidentIdOrderByPerformedAtAsc(String incidentId);
}
