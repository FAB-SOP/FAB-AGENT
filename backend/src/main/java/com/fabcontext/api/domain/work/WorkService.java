package com.fabcontext.api.domain.work;

import java.time.Clock;
import java.time.Instant;
import java.util.*;
import java.util.regex.Pattern;
import com.fabcontext.api.auth.*;
import com.fabcontext.api.common.ApiException;
import com.fabcontext.api.common.Ids;
import com.fabcontext.api.common.stream.IncidentStreamHub;
import com.fabcontext.api.domain.action.*;
import com.fabcontext.api.domain.analysis.*;
import com.fabcontext.api.domain.approval.*;
import com.fabcontext.api.domain.incident.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@lombok.extern.slf4j.Slf4j
@Service
@RequiredArgsConstructor
public class WorkService {
	private final WorkItemRepository repository;
	private final IncidentService incidents;
	private final AnalysisResultRepository results;
	private final UserAccountRepository users;
	private final ApprovalService approvals;
	private final ActionService actions;
	private final IncidentStreamHub stream;
	private final Clock clock;
	private final jakarta.persistence.EntityManager entityManager;
	private final com.fabcontext.api.domain.checkrequest.CheckRequestRepositories.CheckRequestRepository requests;
	private final com.fabcontext.api.domain.processdata.ProcessDataRepositories.RouteRunRepository routes;
	private final com.fabcontext.api.domain.notification.NotificationEvents notificationEvents;
	private final WorkRequestBrief requestBrief;
	private static final Pattern CHANGE = Pattern.compile("조정|수정|재가동|재시작|수리|분해|(?:교체|변경)(?!\\s*(?:이력|기록|여부|시점))|\\b(?:adjust|modify|replace|restart|repair)\\b", Pattern.CASE_INSENSITIVE);

	@Transactional(readOnly=true)
	public List<WorkDtos.View> list(String incidentId, AuthUser user) {
		incidents.loadForUser(incidentId,user);
		return internalList(incidentId);
	}
	@Transactional(readOnly=true)
	public List<WorkDtos.View> internalList(String incidentId) {
		return repository.findByIncidentIdOrderByCreatedAtAsc(incidentId).stream().map(WorkDtos.View::from).toList();
	}
	@Transactional(readOnly=true)
	public List<WorkDtos.View> inbox(AuthUser user, String box) {
		if (!List.of("mine","approval").contains(box)) throw ApiException.badRequest("INVALID_BOX","box는 mine 또는 approval입니다.");
		return repository.findAll().stream().filter(w -> user.hasScope(w.getProcessCode()))
			.filter(w -> w.getStatus()!=WorkItem.Status.PROPOSED && w.getStatus()!=WorkItem.Status.DECLINED)
			.filter(w -> "approval".equals(box) ? user.isManager() && user.userId().equals(w.getApproverUserId()) && w.getStatus()==WorkItem.Status.WAITING_MANAGER
				: user.userId().equals(w.getAssigneeUserId()) || user.userId().equals(w.getRequestedBy()))
			.sorted(Comparator.comparing(WorkItem::getCreatedAt).reversed()).map(WorkDtos.View::from).toList();
	}
	public Map<String,Object> policy() {
		var operations=Arrays.stream(WorkOperation.values()).map(op -> Map.of("operationCode",op.name(),"label",op.label,"route",op.route.name())).toList();
		var directory=users.findAll().stream().map(u -> Map.of("userId",u.getUserId(),"name",u.getDisplayName(),"role",u.getRole().name(),"processScopes",u.getProcessScopes(),"kind","설비정비팀".equals(u.getTeam())?"MAINTENANCE":u.getRole()==Role.MANAGER?"MANAGER":"PROCESS_OWNER")).toList();
		return Map.of("version","WORK-POLICY-V1","operations",operations,"directory",directory,"hardwareExecution",false);
	}
	@Transactional
	public WorkDtos.View propose(String incidentId, AuthUser user, WorkDtos.Proposal body) {
		Incident i=incidents.loadForWrite(incidentId,user); i.requireNotClosed(); i.checkVersion(body.expectedIncidentVersion());
		AnalysisResult r=results.findById(body.sourceResultId()).filter(x -> incidentId.equals(x.getIncidentId()))
			.orElseThrow(() -> ApiException.unprocessable("WORK_SOURCE_INVALID","같은 사건의 분석 결과를 선택하세요."));
		return WorkDtos.View.from(create(i,r,body.operationCode(),body.title(),body.reason(),body.sourceCauseId(),body.evidenceIds(),user.userId()));
	}
	/** Agent의 구조화된 제안만 저장한다. 채택·발송·실행은 여기서 하지 않는다. 이벤트 재수신도 중복 생성하지 않는다. */
	@Transactional
	public void importSuggestions(Incident i, AnalysisResult r, List<Map<String,Object>> suggestions) {
		if (!repository.findBySourceResultId(r.getResultId()).isEmpty()) return;
		if (suggestions.size()>5) throw ApiException.unprocessable("WORK_LIMIT","업무 제안은 분석당 최대 5개입니다.");
		for(var raw:suggestions) {
			WorkOperation op;
			try {op=WorkOperation.valueOf(String.valueOf(raw.get("operationCode")));} catch(RuntimeException e) {op=WorkOperation.REVIEW_REQUIRED;}
			List<String> evidence=raw.get("evidenceIds") instanceof List<?> ids?ids.stream().map(String::valueOf).toList():List.of();
			create(i,r,op,String.valueOf(raw.getOrDefault("title","업무 내용 검토")),String.valueOf(raw.getOrDefault("reason","분석 근거 검토 필요")),null,evidence,"agent");
		}
	}
	private WorkItem create(Incident i, AnalysisResult r, WorkOperation op, String title, String reason, String causeId, List<String> evidence, String creator) {
		if(title.isBlank()||title.length()>2000||reason.isBlank()||reason.length()>2000) throw ApiException.unprocessable("WORK_CONTENT_INVALID","제안 내용과 이유는 1~2000자입니다.");
		if(causeId!=null&&r.getCandidateCauses().stream().noneMatch(c -> causeId.equals(c.get("causeId")))) throw ApiException.unprocessable("WORK_SOURCE_INVALID","분석의 원인 후보가 아닙니다.");
		Set<String> known=new HashSet<>();r.getEvidenceRefs().forEach(e -> known.add(String.valueOf(e.get("evidenceId"))));
		List<String> ev=evidence==null?List.of():List.copyOf(evidence);
		if(!known.containsAll(ev)) throw ApiException.unprocessable("WORK_EVIDENCE_INVALID","이 분석에 포함된 근거만 연결할 수 있습니다.");
		// 일반 확인으로 분류했더라도 변경 표현이 섞이면 관리자 검토로 올린다. 분류 불명확도 관리자 검토다.
		if(op.route!=WorkOperation.Route.MANAGER_APPROVAL&&CHANGE.matcher(title).find()) op=WorkOperation.REVIEW_REQUIRED;
		// 동일 사건에 대한 동시 제안도 직렬화해 새 분석 결과마다 같은 업무가 생기지 않게 한다.
		entityManager.lock(i,jakarta.persistence.LockModeType.PESSIMISTIC_WRITE);
		WorkOperation selected=op;
		var duplicate=repository.findByIncidentIdOrderByCreatedAtAsc(i.getIncidentId()).stream()
			.filter(w->w.getOperationCode()==selected&&WorkSimilarity.same(w.getTitle(),title)).findFirst();
		if(duplicate.isPresent()) return duplicate.get();
		if(op.route==WorkOperation.Route.TEAM_REQUEST) {
			var previous=requests.findByIncidentIdOrderByCreatedAtAsc(i.getIncidentId()).stream()
				.filter(q->(selected==WorkOperation.UPSTREAM_CHECK)==!"MAINTENANCE".equals(q.getRequestType()))
				.filter(q->WorkSimilarity.same(q.getQuestion(),title)).findFirst();
			if(previous.isPresent()) {
				if("agent".equals(creator)) return null;
				throw ApiException.conflict("WORK_ALREADY_REQUESTED","이미 요청한 업무입니다. 기존 요청·회신을 확인하세요.",Map.of("requestId",previous.get().getRequestId()));
			}
		}
		WorkItem w=repository.save(new WorkItem(Ids.next("task"),i.getIncidentId(),i.getProcessCode(),i.getEquipmentId(),op,title,reason,r.getResultId(),causeId,ev,creator,Instant.now(clock)));
		i.record(Instant.now(clock),"WORK_PROPOSED",creator,"업무 제안: "+title,"WORK_ITEM",w.getTaskId());
		publish(w);return w;
	}
	@Transactional
	public WorkDtos.View adopt(String id,AuthUser user,WorkDtos.Adoption body) {
		WorkItem w=loadWrite(id,user,body.expectedVersion());require(w,WorkItem.Status.PROPOSED);
		Incident i=incidents.loadForWrite(w.getIncidentId(),user);i.requireNotClosed();
		AnalysisResult source=null;
		if(w.getRoute()!=WorkOperation.Route.SELF_CHECK) {
			source=results.findById(w.getSourceResultId()).filter(r->i.getIncidentId().equals(r.getIncidentId()))
				.orElseThrow(()->ApiException.unprocessable("WORK_SOURCE_INVALID","같은 사건의 원본 분석 결과를 찾을 수 없습니다."));
		}
		String manager=null;
		if(w.getRoute()==WorkOperation.Route.MANAGER_APPROVAL) {
			manager=users.findAll().stream().filter(u -> u.getRole()==Role.MANAGER&&u.getProcessScopes().contains(w.getProcessCode())&&!u.getUserId().equals(user.userId()))
				.sorted(Comparator.comparingInt((UserAccount u)->u.getProcessScopes().size()).thenComparing(UserAccount::getUserId))
				.findFirst().orElseThrow(() -> ApiException.unprocessable("APPROVER_UNAVAILABLE","요청자와 다른 담당 공정 관리자를 지정해야 합니다.")).getUserId();
		}
		if(w.getRoute()==WorkOperation.Route.MANAGER_APPROVAL) w.captureBrief(requestBrief.forDelivery(w,source,i,i.getProcessCode(),i.getEquipmentId()));
		w.accept(user.userId(),manager,Instant.now(clock));
		notificationEvents.workApprovalRequested(w);
		if(w.getRoute()==WorkOperation.Route.TEAM_REQUEST) {
			UserAccount owner=users.findById(body.ownerUserId()==null?"":body.ownerUserId()).orElseThrow(() -> ApiException.unprocessable("WORK_OWNER_INVALID","등록된 수신 담당자를 선택하세요."));
			String target=body.targetProcessCode();boolean maintenance=w.getOperationCode()!=WorkOperation.UPSTREAM_CHECK;
			boolean eligible=owner.getRole()==Role.ENGINEER&&!owner.getUserId().equals(user.userId())&&owner.getProcessScopes().contains(target)
				&& (maintenance?"설비정비팀".equals(owner.getTeam())&&w.getProcessCode().equals(target):!"설비정비팀".equals(owner.getTeam())&&!w.getProcessCode().equals(target));
			if(!eligible) throw ApiException.unprocessable("WORK_OWNER_INVALID","업무 종류와 대상 공정에 맞는 담당자를 선택하세요.");
			String equipment=w.getEquipmentId();
			if(!maintenance) equipment=routes.findByWaferIdInOrderByWaferIdAscSequenceNoAsc(i.getWaferIds()).stream()
				.filter(r->target.equals(r.getProcessCode())&&r.getSequenceNo()<switch(i.getProcessCode()){case "CMP"->3;case "ETCH"->2;default->1;})
				.findFirst().orElseThrow(()->ApiException.unprocessable("WORK_ROUTE_INVALID","이 웨이퍼의 실제 앞 공정을 선택하세요.")).getEquipmentId();
			w.captureBrief(requestBrief.forDelivery(w,source,i,target,equipment));
			var proposal=approvals.createProposal(i.getIncidentId(),user,new ApprovalDtos.Proposal(maintenance?ApprovalActionType.MAINTENANCE_REQUEST:ApprovalActionType.CHECK_REQUEST,
				Map.of("ownerUserId",owner.getUserId(),"targetProcessCode",target,"question",w.getTitle(),"equipmentId",equipment,"brief",w.getBrief()),w.getReason(),w.getEvidenceIds(),null,i.getVersion()));
			var decision=approvals.decide(proposal.approvalId(),user,new ApprovalDtos.ApprovalDecision("APPROVE","담당자가 업무 이관 발송을 확인함",proposal.version(),proposal.actionHash()));
			w.requested(owner.getUserId(),target,proposal.approvalId(),decision.executionRef().id(),Instant.now(clock));
		}
		i.record(Instant.now(clock),"WORK_ADOPTED",user.userId(),"업무 채택: "+w.getTitle(),"WORK_ITEM",id);
		return changed(w);
	}
	@Transactional
	public WorkDtos.View decide(String id,AuthUser user,WorkDtos.Decision body) {
		WorkItem w=repository.lockById(id).orElseThrow(()->ApiException.notFound("workItem",id));
		Incident i=incidents.loadForWrite(w.getIncidentId(),user);i.requireNotClosed();ApiException.checkVersion("workItem",body.expectedVersion(),w.getVersion());
		if("DECLINE".equals(body.decision())) {require(w,WorkItem.Status.PROPOSED);w.decline(user.userId(),body.comment(),Instant.now(clock));}
		else {
			require(w,WorkItem.Status.WAITING_MANAGER);
			if(!user.isManager()||!user.userId().equals(w.getApproverUserId())||user.userId().equals(w.getRequestedBy())) throw ApiException.forbidden("WORK_APPROVER_REQUIRED","지정된 관리자만 승인·반려할 수 있고 자기 승인은 허용되지 않습니다.");
			if("REJECT".equals(body.decision())&&(body.comment()==null||body.comment().isBlank())) throw ApiException.unprocessable("REJECT_REASON_REQUIRED","반려 사유를 입력하세요.");
			w.decide("APPROVE".equals(body.decision()),body.comment(),Instant.now(clock));
			notificationEvents.workDecided(w,user.userId());
		}
		i.record(Instant.now(clock),"WORK_DECIDED",user.userId(),"업무 결정 "+body.decision()+": "+w.getTitle(),"WORK_ITEM",id);return changed(w);
	}
	@Transactional
	public WorkDtos.View complete(String id,AuthUser user,WorkDtos.Completion body) {
		WorkItem w=loadWrite(id,user,body.expectedVersion());
		if(!user.userId().equals(w.getAssigneeUserId())) throw ApiException.forbidden("WORK_ASSIGNEE_REQUIRED","채택한 담당자만 수행 결과를 기록할 수 있습니다.");
		if(w.getStatus()!=WorkItem.Status.READY&&w.getStatus()!=WorkItem.Status.APPROVED) throw ApiException.conflict("WORK_NOT_READY","채택·관리자 승인 후에만 수행 결과를 기록합니다.",Map.of());
		var recorded=actions.record(w.getIncidentId(),user,new ActionDtos.ActionInput(w.getRoute()==WorkOperation.Route.SELF_CHECK?"INSPECTION":"MANUAL_MAINTENANCE",body.description(),body.performedAt(),w.getEvidenceIds(),body.confirmedByUser(),body.expectedIncidentVersion(),new ActionDtos.SourceRef("WORK_ITEM",id,null)));
		w.complete(recorded.actionId(),Instant.now(clock));return changed(w);
	}
	/** 채택했지만 수행하지 않은 업무를 사유와 함께 닫는다 — 담당자 또는 사건 운영 권한자. 연결된 확인 요청은 바꾸지 않는다 */
	@Transactional
	public WorkDtos.View closeUnperformed(String id,AuthUser user,WorkDtos.UnperformedClosure body) {
		WorkItem w=repository.lockById(id).orElseThrow(()->ApiException.notFound("workItem",id));
		Incident i=user.userId().equals(w.getAssigneeUserId())
			?entityManager.find(Incident.class,w.getIncidentId())
			:incidents.loadForWrite(w.getIncidentId(),user);
		i.requireNotClosed();ApiException.checkVersion("workItem",body.expectedVersion(),w.getVersion());
		w.closeUnperformed(user.userId(),body.reason(),Instant.now(clock));
		i.record(Instant.now(clock),"WORK_CLOSED_UNPERFORMED",user.userId(),"업무 미수행 종결: "+w.getTitle()+" — "+body.reason().strip(),"WORK_ITEM",id);
		return changed(w);
	}
	private WorkItem loadWrite(String id,AuthUser user,long version) {
		WorkItem w=repository.lockById(id).orElseThrow(()->ApiException.notFound("workItem",id));incidents.loadForWrite(w.getIncidentId(),user);
		ApiException.checkVersion("workItem",version,w.getVersion());return w;
	}
	private static void require(WorkItem w,WorkItem.Status status) {if(w.getStatus()!=status) throw ApiException.conflict("WORK_STATE_CONFLICT","현재 상태에서 할 수 없는 작업입니다: "+w.getStatus(),Map.of("status",w.getStatus().name()));}
	private WorkDtos.View changed(WorkItem w) {repository.flush();publish(w);return WorkDtos.View.from(w);}
	private void publish(WorkItem w) {
		String requestId=org.slf4j.MDC.get("requestId"),incidentId=w.getIncidentId(),taskId=w.getTaskId();
		WorkItem.Status status=w.getStatus();
		com.fabcontext.api.common.AfterCommit.run(()->log.info("event=work.status requestId={} incidentId={} taskId={} status={}",requestId,incidentId,taskId,status));
		stream.publish(w.getIncidentId(),"work-item.updated",Map.of("taskId",taskId,"status",status.name()));
	}
}
