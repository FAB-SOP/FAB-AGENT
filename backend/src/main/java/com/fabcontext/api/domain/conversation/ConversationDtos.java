package com.fabcontext.api.domain.conversation;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import com.fabcontext.api.common.Meta;
import com.fabcontext.api.domain.action.ActionDtos.ActionView;
import com.fabcontext.api.domain.analysis.AnalysisDtos.AnalysisResultView;
import com.fabcontext.api.domain.approval.ApprovalDtos.ApprovalView;
import com.fabcontext.api.domain.checkrequest.CheckRequestDtos.CheckRequestView;
import com.fabcontext.api.domain.checkrequest.CheckRequestDtos.ReplyView;
import com.fabcontext.api.domain.investigation.FollowupDtos;
import com.fabcontext.api.domain.job.JobDtos.JobView;
import com.fabcontext.api.domain.work.WorkDtos;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Builder;

public final class ConversationDtos {
    private ConversationDtos() {}
    public enum Kind {
        BASIC_REQUESTED, QUESTION, FOLLOWUP_REQUESTED, REQUEST_DRAFT_REQUESTED, AI_ANSWER,
        APPROVAL_REQUESTED, APPROVAL_DECIDED, REQUEST_SENT, REQUEST_ACCEPTED, REQUEST_REJECTED,
        REQUEST_WITHDRAWN, REPLY, ACTION_RESULT, WORK_PROPOSED, WORK_EVENT, ANALYSIS_FAILED
    }
    @Schema(name = "ConversationActor")
    public record Actor(@Schema(allowableValues = {"HUMAN", "AI", "SYSTEM"}) String type,
            String userId, String displayName) {}
    @Builder
    @Schema(name = "ConversationLinks")
    public record Links(String threadId, String rootJobId, String jobId, String parentJobId,
            String parentEntryId, String resultId, String previousResultId, String sourceResultId,
            String approvalId, String requestId, String replyId, String actionId, String taskId,
            String analysisRequestId,
            @Schema(allowableValues = {"COMPLETE", "MISSING_PARENT", "CYCLE", "UNLINKED"}) String linkStatus) {}
    @Schema(name = "ConversationSelectedInput")
    public record SelectedInput(FollowupDtos.SourceRef sourceRef, String evidenceId, String content,
            String authorUserId, String createdAt, List<String> evidenceIds, boolean includedInInput, Boolean citedInResult) {}
    @Schema(name = "ConversationFollowup")
    public record Followup(String analysisRequestId, String executionMode, String initialJobId,
            String currentJobId, String question, List<SelectedInput> selectedInputs) {}
    @Builder
    @Schema(name = "ConversationData")
    public record Data(AnalysisResultView analysis, ApprovalView approval, CheckRequestView request,
            ReplyView reply, ActionView action, WorkDtos.View work, Followup followup) {}
    @Schema(name = "ConversationPlacement", description = "화면 묶음. Agent thread나 사건 상태와 별개이며 이전 페이지의 부모를 조회하지 않아도 표시할 수 있습니다.")
    public record Placement(String blockId,
            @Schema(allowableValues = {"BASIC", "QUESTION", "FOLLOWUP", "DIRECT"}) String blockType,
            Instant createdAt, @Schema(description = "묶음 제목 요약, 최대 200자. 원 질문은 해당 항목에 보존") String title,
            @Schema(description = "같은 업무·승인·요청 흐름의 안정 ID. 없는 경우 null. 문자열을 해석하지 않고 비교에만 사용") String flowId) {}
    @Schema(name = "ConversationRequestSummary", description = "회신의 원 요청이 이전 페이지에 있어도 제공하는 최소 요약. 전체 brief·근거·회신 원문을 반복하지 않습니다.")
    public record RequestSummary(@Schema(description = "원 요청 요약, 최대 200자") String question,
            String targetProcessCode, String equipmentId) {}
    @Builder
    @Schema(name = "ConversationEntry")
    public record Entry(String entryId, Kind type, Instant occurredAt, Actor actor, String text,
            Links links, JobView job, JobView currentJob, Data data, String eventType,
            FollowupDtos.SourceRef sourceRef, Map<String, Object> analysisUse,
            Placement placement, RequestSummary requestSummary) {}
    @Schema(name = "IncidentConversation")
    public record View(String incidentId, long incidentVersion, Instant generatedAt, List<Entry> items,
            String nextCursor, List<JobView> jobs, List<JobView> runningJobs, List<JobView> waitingJobs,
            List<ApprovalView> pendingApprovals, List<WorkDtos.View> pendingWorkItems,
            AnalysisResultView latestCauseAnalysis, List<FollowupDtos.Result> investigationResults) {}
    @Schema(name = "IncidentConversationEnvelope")
    public record Envelope(View data, Meta meta) {}
}
