package com.fabcontext.api.domain.action;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

public interface IncidentActionRepository extends JpaRepository<IncidentAction, String> {

    /** 통합 대화 조회: 효과 확인 기록도 한 번에 읽어 조치 수만큼 lazy 조회하지 않는다. */
    @org.springframework.data.jpa.repository.EntityGraph(attributePaths = "verifications")
    @org.springframework.data.jpa.repository.Query("select distinct a from IncidentAction a where a.incidentId = :incidentId")
    List<IncidentAction> findConversationActions(String incidentId);

	List<IncidentAction> findByIncidentIdOrderByPerformedAtAsc(String incidentId);
}
