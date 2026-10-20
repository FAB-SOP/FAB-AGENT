package com.fabcontext.api.domain.job;

import java.util.Collection;
import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

public interface JobRepository extends JpaRepository<Job, String> {

	List<Job> findByIncidentIdOrderByCreatedAtDesc(String incidentId);

	List<Job> findByStatusOrderByCreatedAtAsc(JobStatus status);

	boolean existsByIncidentIdAndStatusIn(String incidentId, Collection<JobStatus> statuses);

	boolean existsByIncidentIdAndKindAndStatusIn(String incidentId, JobKind kind, Collection<JobStatus> statuses);

	long countByIncidentIdAndKind(String incidentId, JobKind kind);
}
