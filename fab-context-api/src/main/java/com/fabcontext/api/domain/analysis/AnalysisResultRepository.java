package com.fabcontext.api.domain.analysis;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

public interface AnalysisResultRepository extends JpaRepository<AnalysisResult, String> {

	List<AnalysisResult> findByIncidentIdOrderByCreatedAtDesc(String incidentId);

	Optional<AnalysisResult> findFirstByIncidentIdOrderByCreatedAtDesc(String incidentId);
}
