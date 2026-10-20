package com.fabcontext.api.internal.query;

import java.util.Comparator;
import java.util.List;
import java.util.Map;

import com.fabcontext.api.domain.incident.Incident;
import com.fabcontext.api.domain.incident.IncidentRepository;
import com.fabcontext.api.internal.DemoFaults;
import com.fabcontext.api.internal.InternalError;
import com.fabcontext.api.internal.view.InternalViews;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 업무 MCP 사건 도구 — get_incident · list_incidents. 다른 묶음도 사건 ID(EV-1009 · inc_…) 해석을 여기서 한다 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class InternalIncidentQuery {

	private final IncidentRepository incidentRepository;
	private final DemoFaults faults;
	private final InternalViews views;

	public Map<String, Object> incident(String id) {
		faults.maybeFail("get_incident");
		Incident i = resolve(id);
		return views.env(views.incidentView(i), List.of(views.ref("E-INC-" + i.getDisplayId(), "METROLOGY", i.getDisplayId())), List.of());
	}

	public Map<String, Object> incidents(String processCode, String status) {
		faults.maybeFail("list_incidents");
		List<Map<String, Object>> items = incidentRepository.findAll().stream()
			.filter(i -> processCode == null || processCode.equals(i.getProcessCode()))
			.filter(i -> status == null || "ALL".equals(status) || i.getStatus().name().equals(status))
			.sorted(Comparator.comparing(Incident::getDetectedAt).reversed()).limit(InternalViews.MAX_ITEMS)
			.map(views::incidentView).toList();
		return views.env(Map.of("items", items), List.of(), List.of());
	}

	/** 표시 ID(EV-1009) 또는 inc_ ID로 사건을 찾는다 */
	public Incident resolve(String id) {
		return incidentRepository.findById(id).or(() -> incidentRepository.findByDisplayId(id))
			.orElseThrow(() -> InternalError.notFound("사건 " + id + "를 찾을 수 없습니다.",
				"사건 ID는 EV-1009 같은 표시 ID 또는 inc_로 시작하는 ID입니다."));
	}
}
