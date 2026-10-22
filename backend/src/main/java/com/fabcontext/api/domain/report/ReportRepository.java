package com.fabcontext.api.domain.report;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

public interface ReportRepository extends JpaRepository<Report, String> {

	List<Report> findByIncidentIdOrderByVersionDesc(String incidentId);

	Optional<Report> findByIncidentIdAndVersion(String incidentId, int version);

	Optional<Report> findFirstByIncidentIdOrderByVersionDesc(String incidentId);
}
