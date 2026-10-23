package com.fabcontext.api.domain.priority;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

public interface PriorityEvaluationRepository extends JpaRepository<PriorityEvaluation, String> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select e from PriorityEvaluation e where (e.status = 'PENDING' and e.nextAttemptAt <= :now) "
            + "or (e.status = 'RUNNING' and e.leaseUntil <= :now) order by e.createdAt, e.incidentId")
    List<PriorityEvaluation> runnable(Instant now, Pageable page);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select e from PriorityEvaluation e where e.incidentId = :incidentId")
    Optional<PriorityEvaluation> locked(String incidentId);
}
