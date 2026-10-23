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
import com.fabcontext.api.domain.analysis.AnalysisResultRepository;
import com.fabcontext.api.domain.analysis.AnalysisDtos.AnalysisResultView;
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
	private final AnalysisResultRepository analysisResults;
	private final InternalIncidentQuery incidents;
	private final DemoFaults faults;
	private final InternalViews views;
	private final com.fabcontext.api.domain.work.WorkItemRepository workItems;
	private final com.fabcontext.api.domain.job.JobRepository jobs;

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
		var actions=actionRepository.findByIncidentIdOrderByPerformedAtAsc(i.getIncidentId());
		return views.env(Map.of("items", actions.stream().map(views::actionView).toList()),
			actions.stream().map(a->views.ref("E-ACT-"+a.getActionId(),"ACTION",a.getActionId())).toList(),List.of());
	}

	public Map<String, Object> reportContext(String id) {
		Incident i = incidents.resolve(id);
		Map<String, Object> data = new LinkedHashMap<>();
		data.put("incident", views.incidentView(i));
		// 일반 질문 답이 최신이어도 보고서의 원인 분석을 덮지 않는다. 없는 결과를 만들어 주지 않는다.
		data.put("latestCauseAnalysis", analysisResults.latestCauseAnalysis(i.getIncidentId())
			.map(result -> AnalysisResultView.from(result, null)).orElse(null));
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
		var request=checkRequestRepository.findById(requestId)
			.orElseThrow(() -> InternalError.notFound("요청 " + requestId + "가 없습니다.", ""));
		Map<String,Object> result=views.requestView(request);
		// 기존 Worker의 reply_wait는 reply.memo를 읽는다. 명시적으로 선택한 입력을 그 경로에도 전달한다.
		// 원본 회신 findings나 DB 회신을 수정하지 않으며, 거절을 가짜 회신으로 만들지 않는다.
		if(result.get("reply") instanceof Map<?,?> raw) {
			jobs.findByIncidentIdOrderByCreatedAtDesc(request.getIncidentId()).stream()
				.filter(j->j.getKind()==com.fabcontext.api.domain.job.JobKind.RESUME && j.getStatus().isInProgress())
				.filter(j->requestId.equals(j.getInput().get("requestId")) && j.getInput().containsKey("selectedInputs"))
				.findFirst().ifPresent(job->{
					Map<String,Object> reply=new LinkedHashMap<>(); raw.forEach((key,value)->reply.put(String.valueOf(key),value));
					reply.put("memo",com.fabcontext.api.common.Json.write(Map.of("analysisRequestId",job.getInput().get("analysisRequestId"),
						"question",job.getInput().get("followupQuestion"),"selectedInputs",job.getInput().get("selectedInputs"))));
					result.put("reply",reply);
				});
		}
		return result;
	}
}
