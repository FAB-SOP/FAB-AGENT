package com.fabcontext.api.domain.investigation;

import java.time.Clock;
import java.time.Instant;
import java.util.*;
import com.fabcontext.api.auth.AuthUser;
import com.fabcontext.api.common.*;
import com.fabcontext.api.domain.action.IncidentActionRepository;
import com.fabcontext.api.domain.analysis.AnalysisResult;
import com.fabcontext.api.domain.analysis.AnalysisResultRepository;
import com.fabcontext.api.domain.checkrequest.*;
import com.fabcontext.api.domain.checkrequest.CheckRequestRepositories.*;
import com.fabcontext.api.domain.incident.*;
import com.fabcontext.api.domain.job.*;
import com.fabcontext.api.domain.work.WorkItemRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class FollowupAnalysisService {
	private final IncidentService incidents;
	private final IncidentActionRepository actions;
	private final ReplyRepository replies;
	private final CheckRequestRepository requests;
	private final WorkItemRepository work;
	private final JobRepository jobs;
	private final JobService jobService;
	private final AnalysisRequestRepository repository;
	private final AnalysisResultRepository results;
	private final EntityManager em;
	private final Clock clock;
	private final com.fabcontext.api.config.FabProperties properties;

	@Transactional
	public FollowupDtos.Accepted start(String incidentId,AuthUser user,FollowupDtos.Request body) {
		Incident incident=incidents.loadForWrite(incidentId,user);
		em.refresh(incident,LockModeType.PESSIMISTIC_WRITE);
		incident.requireNotClosed(); incident.checkVersion(body.expectedIncidentVersion());
		if(jobService.hasInProgressInvestigation(incidentId))
			throw ApiException.conflict("ANALYSIS_IN_PROGRESS","이미 진행 중인 작업이 있습니다.",Map.of());
		List<Job> pending=jobs.findByIncidentIdOrderByCreatedAtDesc(incidentId).stream()
			.filter(j->j.getStatus()==JobStatus.WAITING_REPLY).toList();
		if(pending.size()>1) throw ApiException.conflict("ANALYSIS_RESUME_UNAVAILABLE","여러 체크포인트의 재개 순서를 확인해야 합니다.",Map.of());
		if(new HashSet<>(body.sourceRefs()).size()!=body.sourceRefs().size()) throw ApiException.unprocessable("DUPLICATE_SOURCE_REF","같은 결과를 중복 선택할 수 없습니다.");
		List<Map<String,Object>> snapshots=body.sourceRefs().stream().map(ref->snapshot(incidentId,ref)).toList();
		String requestId=Ids.next("arq");
		String question=body.question()==null || body.question().isBlank()?"선택한 확인 결과를 바탕으로 원인 후보와 다음 확인 사항을 갱신해 주세요.":body.question();
		Map<String,Object> input=new LinkedHashMap<>();
		if(com.fabcontext.api.common.Json.write(snapshots).length()>24000) throw ApiException.unprocessable("FOLLOWUP_INPUT_TOO_LARGE","선택한 결과의 합계가 큽니다. 나누어 분석하세요.");
		input.put("followupQuestion",question); input.put("analysisRequestId",requestId); input.put("selectedInputs",snapshots); input.put("trigger","FOLLOWUP_REQUEST");
		Job job;
		String mode;
		if(!pending.isEmpty()) {
			Job waiting=pending.getFirst();
			String expected=waiting.getWaitingRefId();
			Map<String,Object> matching=snapshots.stream().filter(s->expected.equals(s.get("requestId"))).findFirst()
				.orElseThrow(()->ApiException.conflict("WAITING_INPUT_MISMATCH","대기 중인 요청의 회신 또는 거절 결과를 선택하세요.",Map.of("requestId",expected)));
			if(!"REPLY".equals(((Map<?,?>)matching.get("sourceRef")).get("type")))
				throw ApiException.conflict("ANALYSIS_RESUME_UNAVAILABLE","기존 회신 대기 Graph는 거절 사유만으로 재개할 수 없습니다. C 계약 연결이 필요합니다.",Map.of());
			if(properties.agent().external() && waiting.getInput().get("interruptId")==null)
				throw ApiException.conflict("ANALYSIS_RESUME_UNAVAILABLE","외부 Agent 체크포인트의 interruptId가 없습니다.",Map.of());
			input.put("trigger","REPLY"); input.put("requestId",expected); input.put("reason","REPLY_RECEIVED");
			input.put("refId",expected); input.put("interruptId",waiting.getInput().get("interruptId"));
			if("REPLY".equals(((Map<?,?>)matching.get("sourceRef")).get("type"))) input.put("replyId",((Map<?,?>)matching.get("sourceRef")).get("id"));
			job=jobService.resume(waiting,incident,user.userId(),input); mode="RESUME";
		} else {
			if(incident.getActiveJobId()!=null && jobs.findById(incident.getActiveJobId()).filter(j->j.getStatus()==JobStatus.FAILED).isPresent())
				throw ApiException.conflict("ANALYSIS_RESUME_UNAVAILABLE","실패한 기존 실행을 먼저 확인하세요.",Map.of());
			job=jobService.enqueue(incident,JobKind.DEEP,user.userId(),question,"AUTO",input,null,null); mode="NEW";
		}
		repository.save(new AnalysisRequest(requestId,incidentId,user.userId(),job.getJobId(),mode,Instant.now(clock),snapshots));
		incident.record(Instant.now(clock),"FOLLOWUP_REQUESTED",user.userId(),"선택 결과 "+snapshots.size()+"건으로 추가 분석 요청","ANALYSIS_REQUEST",requestId);
		return new FollowupDtos.Accepted(requestId,job.getJobId(),incidentId,incident.getDisplayId(),job.getKind(),job.getStatus(),mode,body.sourceRefs(),
			"/api/analysis-requests/"+requestId,"/api/jobs/"+job.getJobId(),"/api/incidents/"+incidentId+"/stream",false);
	}

	@Transactional(readOnly=true)
	public FollowupDtos.View get(String id,AuthUser user) {
		AnalysisRequest request=repository.findById(id).orElseThrow(()->ApiException.notFound("analysis_request",id));
		incidents.loadForUser(request.getIncidentId(),user);
		Job job=current(request);
		AnalysisResult result=job.getResultRefId()==null?null:results.findById(job.getResultRefId()).orElse(null);
		// 실제 분석 결과가 없는 작업 완료를 성공적인 반영으로 세지 않는다.
		JobStatus status=job.getStatus();
		List<FollowupDtos.Input> inputs=request.getInputs().stream().map(s->{
			var ref=source(s);
			Boolean cited=result==null?null:cited(result,String.valueOf(s.get("evidenceId")));
			return new FollowupDtos.Input(ref,true,cited);
		}).toList();
		return new FollowupDtos.View(id,request.getIncidentId(),request.getRequestedBy(),status,request.getExecutionMode(),request.getInitialJobId(),job.getJobId(),
			result==null?null:result.getResultId(),request.getCreatedAt(),result==null && status!=JobStatus.FAILED?null:job.getFinishedAt(),inputs,
			status==JobStatus.FAILED?Map.of("code",Objects.toString(job.getErrorCode(),"UNKNOWN"),"message",Objects.toString(job.getErrorMessage(),"분석 작업 실패")):null);
	}

	@Transactional(readOnly=true)
	public PageResponse<FollowupDtos.Result> list(String incidentId,AuthUser user,String cursor,Integer size) {
		incidents.loadForUser(incidentId,user);
		List<Map<String,Object>> rows=new ArrayList<>();
		actions.findByIncidentIdOrderByPerformedAtAsc(incidentId).forEach(a->rows.add(snapshot(incidentId,new FollowupDtos.SourceRef("ACTION",a.getActionId()))));
		replies.findByIncidentIdOrderByCreatedAtAsc(incidentId).forEach(r->rows.add(snapshot(incidentId,new FollowupDtos.SourceRef("REPLY",r.getReplyId()))));
		requests.findByIncidentIdOrderByCreatedAtAsc(incidentId).stream().filter(r->r.getStatus()==CheckRequestStatus.REJECTED)
			.forEach(r->rows.add(snapshot(incidentId,new FollowupDtos.SourceRef("REQUEST_DECISION",r.getRequestId()))));
		rows.sort(Comparator.comparing((Map<String,Object> r)->String.valueOf(r.get("createdAt"))).reversed().thenComparing(r->source(r).id()));
		var history=repository.findByIncidentIdOrderByCreatedAtDesc(incidentId);
		return Cursors.page(rows,cursor,size,s->{
			var ref=source(s);
			List<AnalysisRequest> used=history.stream().filter(r->r.getInputs().stream().anyMatch(in->source(in).equals(ref))).toList();
			Map<String,Object> use=new LinkedHashMap<>();
			List<FollowupDtos.View> views=used.stream().map(r->get(r.getAnalysisRequestId(),user)).toList();
			use.put("successfulCount",views.stream().filter(v->v.status()==JobStatus.SUCCEEDED && v.analysisResultId()!=null).count());
			use.put("activeRequestIds",views.stream().filter(v->v.status()!=JobStatus.SUCCEEDED && v.status()!=JobStatus.FAILED).map(FollowupDtos.View::analysisRequestId).toList());
			use.put("lastRequestId",used.isEmpty()?null:used.getFirst().getAnalysisRequestId());
			use.put("lastStatus",views.isEmpty()?null:views.getFirst().status());
			@SuppressWarnings("unchecked") List<String> evidence=(List<String>)s.get("evidenceIds");
			return new FollowupDtos.Result(ref,incidentId,(String)s.get("taskId"),(String)s.get("requestId"),(String)s.get("content"),(String)s.get("authorUserId"),
				Instant.parse(String.valueOf(s.get("createdAt"))),evidence,use);
		});
	}

	private Job current(AnalysisRequest request) {
		return jobs.findByIncidentIdOrderByCreatedAtDesc(request.getIncidentId()).stream()
			.filter(j->request.getAnalysisRequestId().equals(j.getInput().get("analysisRequestId"))).findFirst()
			.orElseGet(()->jobs.findById(request.getInitialJobId()).orElseThrow());
	}
	private Map<String,Object> snapshot(String incidentId,FollowupDtos.SourceRef ref) {
		Map<String,Object> s=new LinkedHashMap<>(); s.put("sourceRef",Map.of("type",ref.type(),"id",ref.id()));
		s.put("incidentId",incidentId);
		switch(ref.type()) {
			case "ACTION" -> {
				var a=actions.findById(ref.id()).filter(v->incidentId.equals(v.getIncidentId())).orElseThrow(()->invalid());
				s.put("content",a.getDescription()); s.put("authorUserId",a.getPerformedBy()); s.put("createdAt",a.getCreatedAt().toString());
				s.put("evidenceIds",List.copyOf(a.getEvidenceIds())); s.put("evidenceId","E-ACT-"+a.getActionId());
				if(a.getRelatedSource()!=null && "WORK_ITEM".equals(a.getRelatedSource().get("type"))) s.put("taskId",a.getRelatedSource().get("refId"));
			}
			case "REPLY" -> {
				var r=replies.findById(ref.id()).filter(v->incidentId.equals(v.getIncidentId())).orElseThrow(()->invalid());
				s.put("requestId",r.getRequestId()); s.put("content",r.getFindings()); s.put("authorUserId",r.getAuthorUserId()); s.put("createdAt",r.getCreatedAt().toString());
				s.put("evidenceIds",List.copyOf(r.getEvidenceIds())); s.put("evidenceId","E-REQ-"+r.getRequestId());
				work.findByRequestId(r.getRequestId()).ifPresent(w->s.put("taskId",w.getTaskId()));
			}
			case "REQUEST_DECISION" -> {
				var r=requests.findById(ref.id()).filter(v->incidentId.equals(v.getIncidentId()) && v.getStatus()==CheckRequestStatus.REJECTED).orElseThrow(()->invalid());
				s.put("requestId",r.getRequestId()); s.put("content",r.getDecisionComment()); s.put("authorUserId",r.getOwnerUserId()); s.put("createdAt",r.getDecisionAt().toString());
				s.put("evidenceIds",List.copyOf(r.getEvidenceIds())); s.put("evidenceId","E-REQ-"+r.getRequestId());
			}
			default -> throw invalid();
		}
		return s;
	}
	private static ApiException invalid() { return ApiException.unprocessable("INVALID_SOURCE_REF","같은 사건의 확인 결과·회신·거절 사유를 선택하세요."); }
	private static FollowupDtos.SourceRef source(Map<String,Object> s) { var ref=(Map<?,?>)s.get("sourceRef"); return new FollowupDtos.SourceRef(String.valueOf(ref.get("type")),String.valueOf(ref.get("id"))); }
	private static boolean cited(AnalysisResult result,String evidenceId) {
		// 근거 목록에 나열된 것과 판단 문장에서 실제 참조한 것을 구분한다.
		return result.getEvidenceRefs().stream().anyMatch(r->evidenceId.equals(r.get("evidenceId")))
			&& (contains(result.getObservedFacts(),evidenceId) || contains(result.getCandidateCauses(),evidenceId));
	}
	private static boolean contains(Object value,String id) {
		if(value instanceof Map<?,?> m) return m.values().stream().anyMatch(v->contains(v,id));
		if(value instanceof Collection<?> c) return c.stream().anyMatch(v->contains(v,id));
		return id.equals(value);
	}
}
