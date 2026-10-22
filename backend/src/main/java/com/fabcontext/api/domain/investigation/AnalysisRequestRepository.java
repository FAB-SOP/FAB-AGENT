package com.fabcontext.api.domain.investigation;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AnalysisRequestRepository extends JpaRepository<AnalysisRequest,String> {
	List<AnalysisRequest> findByIncidentIdOrderByCreatedAtDesc(String incidentId);
}
