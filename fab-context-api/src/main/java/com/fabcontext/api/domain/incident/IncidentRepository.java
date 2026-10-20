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

	/** 권한 공정의 사건 가운데 그 웨이퍼를 담은 것이 있는지 — wafer_ids(jsonb) 포함 연산자 */
	@Query(value = """
		select exists(select 1 from {h-schema}incident
		where process_code in (:processCodes) and wafer_ids @> jsonb_build_array(cast(:waferId as text)))""", nativeQuery = true)
	boolean existsByProcessCodeInAndWafer(Collection<String> processCodes, String waferId);

	/** run ID마다 그 run을 담은 사건 — run_ids(jsonb)를 펼쳐 찾고, 여럿이면 먼저 검출된 것이 앞에 온다 */
	@Query(value = """
		select r.run_id as "runId", i.incident_id as "incidentId"
		from {h-schema}incident i cross join lateral jsonb_array_elements_text(i.run_ids) as r(run_id)
		where r.run_id in (:runIds) order by i.detected_at, i.incident_id""", nativeQuery = true)
	List<RunIncident> findByRunIds(Collection<String> runIds);

	interface RunIncident {

		String getRunId();

		String getIncidentId();
	}
}
