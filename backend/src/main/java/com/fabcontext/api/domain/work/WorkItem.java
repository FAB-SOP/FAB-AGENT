package com.fabcontext.api.domain.work;

import java.time.Instant;
import java.util.List;
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
	public enum Status { PROPOSED, DECLINED, READY, WAITING_MANAGER, APPROVED, REJECTED, REQUESTED, COMPLETED }
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
	@Version private long version;

	public WorkItem(String id, String incidentId, String processCode, String equipmentId, WorkOperation op,
			String title, String reason, String resultId, String causeId, List<String> evidence, String creator, Instant now) {
		this.taskId=id; this.incidentId=incidentId; this.processCode=processCode; this.equipmentId=equipmentId;
		this.operationCode=op; this.route=op.route; this.title=title; this.reason=reason;
		this.sourceResultId=resultId; this.sourceCauseId=causeId; this.evidenceIds=List.copyOf(evidence);
		this.createdBy=creator; this.status=Status.PROPOSED; this.createdAt=now; this.updatedAt=now; this.version=1;
	}
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
	public void completeFromReply(String reply, Instant now) { replyId=reply; status=Status.COMPLETED; updatedAt=now; }
}
