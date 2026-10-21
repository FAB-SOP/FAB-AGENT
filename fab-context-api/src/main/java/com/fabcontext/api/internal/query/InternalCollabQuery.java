package com.fabcontext.api.internal.query;

import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.fabcontext.api.auth.UserAccount;
import com.fabcontext.api.auth.UserAccountRepository;
import com.fabcontext.api.domain.action.IncidentActionRepository;
import com.fabcontext.api.domain.checkrequest.CheckRequestRepositories.CheckRequestRepository;
import com.fabcontext.api.domain.incident.Incident;
import com.fabcontext.api.domain.report.ReportRepository;
import com.fabcontext.api.internal.DemoFaults;
import com.fabcontext.api.internal.InternalError;
import com.fabcontext.api.internal.view.InternalViews;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 업무 MCP 협업 · 기록 도구 — get_process_owner · get_check_requests · 조치 · 보고서 맥락, 확인 요청 하나(재개 값 조회) */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class InternalCollabQuery {

	private final UserAccountRepository userRepository;
	private final CheckRequestRepository checkRequestRepository;
	private final IncidentActionRepository actionRepository;
	private final ReportRepository reportRepository;
	private final InternalIncidentQuery incidents;
	private final DemoFaults faults;
	private final InternalViews views;
	private final com.fabcontext.api.domain.work.WorkItemRepository workItems;

	public Map<String, Object> owners(String processCode) {
		faults.maybeFail("get_process_owner");
		List<Map<String, Object>> items = userRepository.findEngineersByProcess(processCode).stream()
			.sorted(Comparator.comparing((UserAccount u) -> InternalViews.MAINTENANCE_TEAM.equals(u.getTeam())))
			.map(u -> {
				Map<String, Object> m = new LinkedHashMap<>();
				m.put("userId", u.getUserId());
				m.put("processCode", processCode);
				m.put("kind", InternalViews.MAINTENANCE_TEAM.equals(u.getTeam()) ? "MAINTENANCE" : "PROCESS_OWNER");
				m.put("displayName", "(가림)"); // 이름은 모델에 보내지 않는다
				return m;
			}).toList();
		return views.env(Map.of("items", items), List.of(), List.of());
	}

	public Map<String, Object> checkRequests(String id) {
		faults.maybeFail("get_check_requests");
		Incident i = incidents.resolve(id);
		List<Map<String, Object>> rows = checkRequestRepository.findByIncidentIdOrderByCreatedAtAsc(i.getIncidentId())
			.stream().map(views::requestView).toList();
		List<Map<String, Object>> ev = rows.stream().filter(r -> r.get("reply") != null)
			.map(r -> views.ref("E-REQ-" + r.get("requestId"), "REPLY", (String) r.get("requestId"))).toList();
		return views.env(Map.of("items", rows), ev, List.of());
	}

	public Map<String, Object> actions(String id) {
		Incident i = incidents.resolve(id);
		return views.env(Map.of("items", actionRepository.findByIncidentIdOrderByPerformedAtAsc(i.getIncidentId()).stream()
			.map(views::actionView).toList()), List.of(), List.of());
	}

	public Map<String, Object> reportContext(String id) {
		Incident i = incidents.resolve(id);
		Map<String, Object> data = new LinkedHashMap<>();
		data.put("incident", views.incidentView(i));
		data.put("checkRequests", checkRequestRepository.findByIncidentIdOrderByCreatedAtAsc(i.getIncidentId()).stream()
			.map(views::requestView).toList());
		data.put("actions", actionRepository.findByIncidentIdOrderByPerformedAtAsc(i.getIncidentId()).stream()
			.map(views::actionView).toList());
		data.put("reports", reportRepository.findByIncidentIdOrderByVersionDesc(i.getIncidentId()).stream()
			.map(r -> Map.<String, Object>of("reportId", r.getReportId(), "version", r.getVersion(), "status",
				r.getStatus().name()))
			.toList());
		data.put("workItems",workItems.findByIncidentIdOrderByCreatedAtAsc(i.getIncidentId()).stream().map(com.fabcontext.api.domain.work.WorkDtos.View::from).toList());
		data.put("followupNote", "후속 확인 run 결과가 없으면 해결 완료로 보고하지 않습니다.");
		return views.env(data, List.of(views.ref("E-RPTCTX-" + i.getDisplayId(), "REPORT_CONTEXT", i.getDisplayId())), List.of());
	}

	public Map<String, Object> checkRequest(String requestId) {
		return views.requestView(checkRequestRepository.findById(requestId)
			.orElseThrow(() -> InternalError.notFound("요청 " + requestId + "가 없습니다.", "")));
	}
}
