package com.fabcontext.api.domain.job;

import org.springframework.data.jpa.repository.JpaRepository;

public interface ProcessedAgentEventRepository extends JpaRepository<ProcessedAgentEvent, String> {
}
