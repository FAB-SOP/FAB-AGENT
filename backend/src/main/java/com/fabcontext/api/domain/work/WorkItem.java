package com.fabcontext.api.domain.work;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import com.fabcontext.api.common.ApiException;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "work_item", indexes = {@Index(columnList="incidentId"), @Index(columnList="approverUserId,status"), @Index(columnList="requestId")})
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class WorkItem {
	public enum Status { PROPOSED, DECLINED, READY, WAITING_MANAGER, APPROVED, REJECTED, REQUESTED, COMPLETED, CLOSED_UNPERFORMED }
	@Id private String taskId;
	private String incidentId;
	private String processCode;
	private String equipmentId;
	@Enumerated(EnumType.STRING) private WorkOperation operationCode;
	@Enumerated(EnumType.STRING) private WorkOperation.Route route;
	@Column(length=2000) private String title;
	@Column(length=2000) private String reason;
	private String sourceResultId;
	private String sourceCauseId;
	@JdbcTypeCode(SqlTypes.JSON) private List<String> evidenceIds;
	/** 채택·발송 시 보존한 조사 맥락. 기존 업무와 직접 확인 업무는 null일 수 있다. */
	@JdbcTypeCode(SqlTypes.JSON) private Map<String,Object> brief;
	private String createdBy;
	private String requestedBy;
	private String assigneeUserId;
	private String approverUserId;
	private String targetProcessCode;
	private String requestId;
	private String requestApprovalId;
	private String actionId;
	private String replyId;
	@Column(length=2000) private String decisionComment;
	@Enumerated(EnumType.STRING) private Status status;
	private Instant createdAt;
	private Instant updatedAt;
	/** 미수행 종결 — 채택했지만 수행하지 않은 업무를 사유와 함께 닫는다 */
	private String closedBy;
	private Instant closedAt;
	@Column(length=500) private String closeReason;
	@Version private long version;

	public WorkItem(String id, String incidentId, String processCode, String equipmentId, WorkOperation op,
			String title, String reason, String resultId, String causeId, List<String> evidence, String creator, Instant now) {
		this.taskId=id; this.incidentId=incidentId; this.processCode=processCode; this.equipmentId=equipmentId;
		this.operationCode=op; this.route=op.route; this.title=title; this.reason=reason;
		this.sourceResultId=resultId; this.sourceCauseId=causeId; this.evidenceIds=List.copyOf(evidence);
		this.createdBy=creator; this.status=Status.PROPOSED; this.createdAt=now; this.updatedAt=now; this.version=1;
	}
	void captureBrief(Map<String,Object> value) { if(brief==null) brief=new java.util.LinkedHashMap<>(value); }
	void decline(String user, String comment, Instant now) { requestedBy=user; status=Status.DECLINED; decisionComment=comment; updatedAt=now; }
	void accept(String user, String manager, Instant now) {
		requestedBy=user; assigneeUserId=user; approverUserId=manager; updatedAt=now;
		status=route==WorkOperation.Route.MANAGER_APPROVAL?Status.WAITING_MANAGER:Status.READY;
	}
	void requested(String owner, String process, String approval, String request, Instant now) {
		assigneeUserId=owner; targetProcessCode=process; requestApprovalId=approval; requestId=request; status=Status.REQUESTED; updatedAt=now;
	}
	void decide(boolean approved, String comment, Instant now) { status=approved?Status.APPROVED:Status.REJECTED; decisionComment=comment; updatedAt=now; }
	void complete(String action, Instant now) { actionId=action; status=Status.COMPLETED; updatedAt=now; }
	void closeUnperformed(String user, String reason, Instant now) {
		if(status!=Status.READY&&status!=Status.APPROVED&&status!=Status.REQUESTED)
			throw ApiException.conflict("WORK_NOT_CLOSABLE","채택했지만 수행하지 않은 업무(READY · APPROVED · REQUESTED)만 미수행으로 닫을 수 있습니다.",Map.of("status",status.name()));
		closedBy=user; closedAt=now; closeReason=reason.strip(); status=Status.CLOSED_UNPERFORMED; updatedAt=now;
	}
	public void rejectFromRequest(String reason, Instant now) { status=Status.REJECTED; decisionComment=reason; updatedAt=now; }
	public void completeFromReply(String reply, Instant now) { replyId=reply; status=Status.COMPLETED; updatedAt=now; }
}
