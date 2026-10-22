package com.fabcontext.api.domain.processdata;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

public final class ProcessDataRepositories {

	private ProcessDataRepositories() {
	}

	public interface MetrologyRepository extends JpaRepository<Metrology, Long> {

		List<Metrology> findByWaferIdInOrderByWaferIdAscStageAscSiteIdAsc(Collection<String> waferIds);

		List<Metrology> findByRunIdOrderBySiteIdAsc(String runId);
	}

	public interface RouteRunRepository extends JpaRepository<RouteRun, Long> {

		Optional<RouteRun> findByRunId(String runId);

		List<RouteRun> findByWaferIdOrderBySequenceNoAsc(String waferId);

		List<RouteRun> findByWaferIdInOrderByWaferIdAscSequenceNoAsc(Collection<String> waferIds);

		List<RouteRun> findByRunIdIn(Collection<String> runIds);

		List<RouteRun> findByPhase(String phase);

		List<RouteRun> findByPhaseAndProcessCode(String phase, String processCode);
	}

	public interface RunFeatureRepository extends JpaRepository<RunFeature, Long> {

		List<RunFeature> findByRunIdOrderBySensorIdAsc(String runId);

		List<RunFeature> findByPhase(String phase);
	}

	public interface ChangeRecordRepository extends JpaRepository<ChangeRecord, String> {

		List<ChangeRecord> findByEquipmentIdInOrderByOccurredAtDesc(Collection<String> equipmentIds);
	}
}
