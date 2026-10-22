package com.fabcontext.api.domain.work;
import java.util.List;
import java.util.Optional;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;
public interface WorkItemRepository extends JpaRepository<WorkItem,String> {
	List<WorkItem> findByIncidentIdOrderByCreatedAtAsc(String incidentId);
	Optional<WorkItem> findByRequestId(String requestId);
	List<WorkItem> findBySourceResultId(String sourceResultId);
	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@Query("select w from WorkItem w where w.taskId=:id")
	Optional<WorkItem> lockById(@Param("id") String id);
}
