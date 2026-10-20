package com.fabcontext.api.domain.incident;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface IncidentRepository extends JpaRepository<Incident, String> {

	List<Incident> findByProcessCodeInOrderByDetectedAtDesc(Collection<String> processCodes);

	Optional<Incident> findBySourceEventId(String sourceEventId);

	Optional<Incident> findByDisplayId(String displayId);

	@Query("select coalesce(max(i.displaySeq), 1000) from Incident i")
	int findMaxDisplaySeq();
}
