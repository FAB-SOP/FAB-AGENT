package com.fabcontext.api.domain.job;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;

public interface ProcessedAgentEventRepository extends JpaRepository<ProcessedAgentEvent, String> {
	List<ProcessedAgentEvent> findByJobIdAndAttemptAndEventTypeIn(String jobId, int attempt, Collection<String> eventTypes);
}
