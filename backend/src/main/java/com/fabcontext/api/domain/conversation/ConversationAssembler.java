package com.fabcontext.api.domain.conversation;

import java.time.Instant;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

import com.fabcontext.api.domain.action.*;
import com.fabcontext.api.domain.analysis.*;
import com.fabcontext.api.domain.approval.*;
import com.fabcontext.api.domain.checkrequest.*;
import com.fabcontext.api.domain.incident.*;
import com.fabcontext.api.domain.investigation.*;
import com.fabcontext.api.domain.job.*;
import com.fabcontext.api.domain.job.JobDtos.JobView;
import com.fabcontext.api.domain.work.*;
import static com.fabcontext.api.domain.conversation.ConversationDtos.*;

/** 이미 사건 범위로 모은 자료만 연결하는 순수 변환기. 개별 자원 조회나 상태 변경을 하지 않는다. */
final class ConversationAssembler {
    final Incident incident;
    final List<Job> jobs;
    final List<AnalysisResult> results;
    final List<Approval> approvals;
    final List<CheckRequest> requests;
    final List<Reply> replies;
    final List<IncidentAction> actions;
    final List<WorkItem> works;
    final List<AnalysisRequest> followups;
    final Map<String, Job> jobById;
    final Map<String, AnalysisResult> resultById;
    final Map<String, Approval> approvalById;
    final Map<String, CheckRequest> requestById;
    final Map<String, WorkItem> workById;
    final Map<String, AnalysisRequest> followupById;
    final Map<String, AnalysisRequest> followupByInitial;
    final String userId;

    ConversationAssembler(Incident incident, List<Job> jobs, List<AnalysisResult> results, List<Approval> approvals,
            List<CheckRequest> requests, List<Reply> replies, List<IncidentAction> actions, List<WorkItem> works,
            List<AnalysisRequest> followups, String userId) {
        this.incident = incident; this.jobs = jobs; this.results = results; this.approvals = approvals;
        this.requests = requests; this.replies = replies; this.actions = actions; this.works = works; this.followups = followups; this.userId = userId;
        jobById = index(jobs, Job::getJobId); resultById = index(results, AnalysisResult::getResultId);
        approvalById = index(approvals, Approval::getApprovalId); requestById = index(requests, CheckRequest::getRequestId);
        workById = index(works, WorkItem::getTaskId); followupById = index(followups, AnalysisRequest::getAnalysisRequestId);
        followupByInitial = index(followups, AnalysisRequest::getInitialJobId);
    }
    private static <T> Map<String, T> index(List<T> items, Function<T, String> id) {
        return items.stream().collect(Collectors.toMap(id, Function.identity(), (first, later) -> first, LinkedHashMap::new));
    }
    Set<String> actorIds() {
        Set<String> ids = new HashSet<>();
        jobs.forEach(j -> ids.add(j.getRequestedBy()));
        approvals.forEach(a -> { ids.add(a.getCreatedBy()); ids.add(a.getDecidedBy()); });
        requests.forEach(r -> { ids.add(r.getRequesterUserId()); ids.add(r.getOwnerUserId()); ids.add(r.getWithdrawnBy()); });
        replies.forEach(r -> ids.add(r.getAuthorUserId())); actions.forEach(a -> ids.add(a.getPerformedBy()));
        works.forEach(w -> ids.add(w.getCreatedBy())); followups.forEach(a -> ids.add(a.getRequestedBy()));
        incident.getTimeline().forEach(t -> ids.add(t.getActorId())); ids.remove(null); return ids;
    }
    private Actor actor(String id, boolean ai, Map<String, String> names) {
        if (ai || "agent".equals(id)) return new Actor("AI", null, "AI");
        if (id == null || !names.containsKey(id)) return new Actor("SYSTEM", id, "시스템");
        return new Actor("HUMAN", id, names.get(id));
    }
    private Instant at(Instant value) { return value != null ? value : incident.getCreatedAt() != null ? incident.getCreatedAt() : Instant.EPOCH; }
    private Entry.EntryBuilder entry(String id, Kind kind, Instant time, String actorId, String text, Job job, Map<String, String> names) {
        return Entry.builder().entryId(id).type(kind).occurredAt(at(time)).actor(actor(actorId, kind == Kind.AI_ANSWER, names))
                .text(text).job(job == null ? null : JobView.from(job)).currentJob(job == null ? null : JobView.from(tail(job)))
                .links(links(job).build()).placement(placement(job, null));
    }
    private Links.LinksBuilder links(Job job) {
        if (job == null) return Links.builder().linkStatus("UNLINKED");
        Job root = job; Set<String> seen = new HashSet<>(); String status = "COMPLETE";
        while (root.getParentJobId() != null) {
            if (!seen.add(root.getJobId())) { status = "CYCLE"; break; }
            Job parent = jobById.get(root.getParentJobId());
            if (parent == null) { status = "MISSING_PARENT"; break; }
            root = parent;
        }
        return Links.builder().jobId(job.getJobId()).parentJobId(job.getParentJobId()).threadId(job.getThreadId())
                .rootJobId(root.getJobId()).linkStatus(status);
    }
    private AnalysisRequest followup(Job job) {
        if (job == null) return null;
        Object id = job.getInput().get("analysisRequestId");
        if (id instanceof String key && followupById.containsKey(key)) return followupById.get(key);
        Set<String> seen = new HashSet<>();
        while (job != null && seen.add(job.getJobId())) {
            AnalysisRequest found = followupByInitial.get(job.getJobId());
            if (found != null) return found;
            job = jobById.get(job.getParentJobId());
        }
        return null;
    }
    private Job current(AnalysisRequest request) { return tail(jobById.get(request.getInitialJobId())); }
    private Job tail(Job job) {
        Set<String> seen = new HashSet<>();
        while (job != null && "RESUME_JOB".equals(job.getResultRefType()) && seen.add(job.getJobId())) {
            Job child = jobById.get(job.getResultRefId());
            if (child == null || seen.contains(child.getJobId())) break;
            job = child;
        }
        return job;
    }
    private AnalysisResult finalResult(AnalysisRequest request) {
        Job current = current(request);
        return current != null && "ANALYSIS_RESULT".equals(current.getResultRefType()) ? resultById.get(current.getResultRefId()) : null;
    }
    private String jobEntry(Job job) {
        AnalysisRequest request = followup(job);
        if (request != null) return "followup:" + request.getAnalysisRequestId();
        Links link = links(job).build();
        return link.rootJobId() == null ? null : "job:" + link.rootJobId() + ":requested";
    }
    private Job approvalJob(Approval approval) {
        if (approval == null) return null;
        Job job = jobById.get(approval.getJobId());
        return job != null ? job : works.stream().filter(w -> approval.getApprovalId().equals(w.getRequestApprovalId()))
                .map(this::workJob).filter(Objects::nonNull).findFirst().orElse(null);
    }
    private WorkItem requestWork(String requestId) { return works.stream().filter(w -> requestId.equals(w.getRequestId())).findFirst().orElse(null); }
    private Job workJob(WorkItem work) { return work == null ? null : resultJob(work.getSourceResultId()); }
    private Job resultJob(String resultId) { var result = resultById.get(resultId); return result == null ? null : jobById.get(result.getJobId()); }
    private Job requestJob(CheckRequest request) {
        Job job = approvalJob(approvalById.get(request.getApprovalId()));
        if (job == null) job = workJob(requestWork(request.getRequestId()));
        if (job == null) job = jobById.get(request.getWaitingJobId());
        return job;
    }
    private WorkItem approvalWork(Approval approval) {
        return approval == null ? null : works.stream().filter(w -> approval.getApprovalId().equals(w.getRequestApprovalId())).findFirst().orElse(null);
    }
    private String source(String id) { return id != null && resultById.containsKey(id) ? id : null; }
    private String requestSource(CheckRequest request, WorkItem work) {
        if (work != null && source(work.getSourceResultId()) != null) return work.getSourceResultId();
        Approval approval = approvalById.get(request.getApprovalId());
        if (approval != null && source(approval.getSourceResultId()) != null) return approval.getSourceResultId();
        Job job = requestJob(request);
        return job != null && job.getInput().get("sourceResultId") instanceof String id ? source(id) : null;
    }
    private static String shortText(String value) {
        if (value == null) return null;
        String text = value.replaceAll("\\s+", " ").strip();
        return text.codePointCount(0, text.length()) <= 200 ? text : text.substring(0, text.offsetByCodePoints(0, 199)) + "…";
    }
    /** 새 선택 결과 분석은 같은 checkpoint RESUME여도 독립 블록이다. 요청서 버튼은 기준 분석 블록에 붙인다. */
    private Placement placement(Job job, String flowId) {
        Set<String> seen = new HashSet<>();
        while (job != null && seen.add(job.getJobId())) {
            AnalysisRequest selected = followup(job);
            if (selected != null) {
                Job initial = jobById.get(selected.getInitialJobId());
                String question = initial == null ? null : Objects.toString(initial.getInput().getOrDefault("followupQuestion", initial.getQuestion()), null);
                return new Placement("followup:" + selected.getAnalysisRequestId(), "FOLLOWUP", at(selected.getCreatedAt()), shortText(question), flowId);
            }
            if (job.getParentJobId() != null) { job = jobById.get(job.getParentJobId()); continue; }
            if ("REQUEST".equals(job.getInput().get("purpose")) && job.getInput().get("sourceResultId") instanceof String id) {
                job = resultJob(id); continue;
            }
            return new Placement("job:" + job.getJobId() + ":requested", job.getKind() == JobKind.BASIC ? "BASIC" : "QUESTION",
                    at(job.getCreatedAt()), job.getKind() == JobKind.BASIC ? "기본 분석" : shortText(job.getQuestion()), flowId);
        }
        return null; // 다른 사건/누락/순환 참조를 따라가거나 인접한 분석에 추측해서 붙이지 않는다.
    }
    private Placement resourcePlacement(Job job, String flowId, Instant createdAt, String title) {
        Placement found = placement(job, flowId);
        return found != null ? found : new Placement(flowId, "DIRECT", at(createdAt), shortText(title), flowId);
    }
    private Placement workPlacement(WorkItem work) {
        return resourcePlacement(workJob(work), "work:" + work.getTaskId(), work.getCreatedAt(), work.getTitle());
    }
    private Placement approvalPlacement(Approval approval, WorkItem work) {
        if (work != null) return workPlacement(work);
        Job job = resultJob(approval.getSourceResultId());
        return resourcePlacement(job == null ? approvalJob(approval) : job, "approval:" + approval.getApprovalId(), approval.getCreatedAt(), approval.getRationale());
    }
    private Placement requestPlacement(CheckRequest request, WorkItem work) {
        if (work != null) return workPlacement(work);
        Approval approval = approvalById.get(request.getApprovalId());
        return approval != null ? approvalPlacement(approval, null)
                : resourcePlacement(requestJob(request), "request:" + request.getRequestId(), request.getCreatedAt(), request.getQuestion());
    }
    private Links.LinksBuilder requestLinks(CheckRequest request, Job job, WorkItem work) {
        return links(job).requestId(request.getRequestId()).approvalId(request.getApprovalId())
                .taskId(work == null ? null : work.getTaskId()).sourceResultId(requestSource(request, work));
    }
    boolean pendingCard(Approval approval) {
        if (approval.getStatus() != ApprovalStatus.PENDING) return false;
        if (!"AGENT".equals(approval.getSource())) return true;
        Job job = jobById.get(approval.getJobId());
        return job != null && job.getStatus() == JobStatus.WAITING_APPROVAL && approval.getApprovalId().equals(job.getWaitingRefId());
    }

    private CheckRequestDtos.CheckRequestView requestView(CheckRequest request) {
        return new CheckRequestDtos.CheckRequestView(request.getRequestId(), request.getIncidentId(), null,
                request.getTargetProcessCode(), request.getApprovalId(), request.getRequestType(), request.getEquipmentId(), request.getQuestion(), request.getBrief(),
                request.getCauseIds(), request.getEvidenceIds(), request.getRequesterUserId(), request.getOwnerUserId(), request.getStatus(), request.getVersion(), request.getThreadId(),
                request.getWaitingJobId(), request.getDueAt(), request.getCreatedAt(), request.getRepliedAt(), List.of(), request.getDecisionAt(), request.getDecisionComment(),
                CheckRequestDtos.allowed(request, userId), request.getWithdrawnBy(), request.getWithdrawnAt(), request.getWithdrawReason());
    }

    List<Entry> entries(Map<String, String> names) {
        List<Entry> out = new ArrayList<>();
        for (Job job : jobs) {
            if (job.getKind() != JobKind.RESUME && followupByInitial.get(job.getJobId()) == null) {
                boolean draft = "REQUEST".equals(job.getInput().get("purpose"));
                Kind kind = job.getKind() == JobKind.BASIC ? Kind.BASIC_REQUESTED : draft ? Kind.REQUEST_DRAFT_REQUESTED : Kind.QUESTION;
                String source = job.getInput().get("sourceResultId") instanceof String id && resultById.containsKey(id) ? id : null;
                String text = job.getKind() == JobKind.BASIC ? "기본 분석 요청" : draft ? "확인 요청서 작성 요청" : job.getQuestion();
                out.add(entry("job:" + job.getJobId() + ":requested", kind, job.getCreatedAt(), job.getRequestedBy(), text, job, names)
                        .links(links(job).sourceResultId(source).parentEntryId(source == null ? null : "result:" + source).build()).build());
            }
            if (job.getStatus() == JobStatus.FAILED) out.add(entry("job:" + job.getJobId() + ":failed", Kind.ANALYSIS_FAILED,
                    job.getFinishedAt(), null, job.getErrorMessage(), job, names).links(links(job).parentEntryId(jobEntry(job)).build()).build());
        }
        for (AnalysisRequest request : followups) {
            Job job = jobById.get(request.getInitialJobId());
            var selected = safeInputs(request);
            String question = job == null ? null : Objects.toString(job.getInput().getOrDefault("followupQuestion", job.getQuestion()), null);
            out.add(entry("followup:" + request.getAnalysisRequestId(), Kind.FOLLOWUP_REQUESTED, request.getCreatedAt(), request.getRequestedBy(), question, job, names)
                    .links(links(job).analysisRequestId(request.getAnalysisRequestId()).parentEntryId(job != null && job.getParentJobId() != null ? jobEntry(jobById.get(job.getParentJobId())) : null).build())
                    .data(Data.builder().followup(new Followup(request.getAnalysisRequestId(), request.getExecutionMode(), request.getInitialJobId(),
                            current(request) == null ? null : current(request).getJobId(), question, selected)).build()).build());
        }
        for (AnalysisResult result : results) {
            Job job = jobById.get(result.getJobId());
            if (job == null) continue; // 보고서/요약 결과나 다른 사건 참조를 조사 대화로 가져오지 않는다.
            String parent = result.getPreviousResultId() != null && resultById.containsKey(result.getPreviousResultId())
                    ? "result:" + result.getPreviousResultId() : jobEntry(job);
            String approvalId = job.getInput().get("approvalId") instanceof String id && approvalById.containsKey(id) ? id : null;
            if (parent != null && followup(job) == null && approvalId != null && approvalById.get(approvalId).getDecidedAt() != null)
                parent = "approval:" + approvalId + ":decided";
            AnalysisRequest followup = followup(job);
            out.add(entry("result:" + result.getResultId(), Kind.AI_ANSWER, result.getCreatedAt(), null,
                    result.getAnswer() == null ? result.getSummary() : result.getAnswer(), job, names)
                    .links(links(job).resultId(result.getResultId()).previousResultId(result.getPreviousResultId()).approvalId(approvalId)
                            .analysisRequestId(followup == null ? null : followup.getAnalysisRequestId()).parentEntryId(parent).build())
                    .data(Data.builder().analysis(AnalysisDtos.AnalysisResultView.from(result, null)).build()).build());
        }
        for (Approval approval : approvals) {
            // 도구 호출이 요청서를 저장한 직후에는 아직 HITL/답 공개 전일 수 있다.
            if (approval.getStatus() == ApprovalStatus.PENDING && "AGENT".equals(approval.getSource()) && !pendingCard(approval)) continue;
            Job job = approvalJob(approval);
            WorkItem work = approvalWork(approval);
            String sourceId = source(approval.getSourceResultId());
            if (sourceId == null && work != null) sourceId = source(work.getSourceResultId());
            Placement display = approvalPlacement(approval, work);
            var data = Data.builder().approval(ApprovalDtos.ApprovalView.from(approval)).build();
            // 요청서 생성은 답변 공개보다 앞설 수 있다. 함께 공개된 답변 다음에 카드를 배치한다.
            AnalysisResult basis = resultById.get(sourceId);
            Instant publishedAt = basis != null && at(basis.getCreatedAt()).isAfter(at(approval.getCreatedAt())) ? basis.getCreatedAt() : approval.getCreatedAt();
            out.add(entry("approval:" + approval.getApprovalId() + ":requested", Kind.APPROVAL_REQUESTED, publishedAt, approval.getCreatedBy(), approval.getRationale(), job, names)
                    .links(links(job).approvalId(approval.getApprovalId()).taskId(work == null ? null : work.getTaskId()).sourceResultId(sourceId).parentEntryId(
                            work != null ? "work:" + work.getTaskId() + ":proposed" : sourceId != null ? "result:" + sourceId : jobEntry(job)).build())
                    .placement(display).data(data).build());
            if (approval.getDecidedAt() != null)
                out.add(entry("approval:" + approval.getApprovalId() + ":decided", Kind.APPROVAL_DECIDED, approval.getDecidedAt(), approval.getDecidedBy(), approval.getDecisionComment(), job, names)
                        .links(links(job).approvalId(approval.getApprovalId()).taskId(work == null ? null : work.getTaskId()).sourceResultId(sourceId)
                                .parentEntryId("approval:" + approval.getApprovalId() + ":requested").build())
                        .placement(display).eventType(approval.getStatus().name()).data(data).build());
        }
        for (CheckRequest request : requests) {
            Job job = requestJob(request); WorkItem work = requestWork(request.getRequestId());
            Placement display = requestPlacement(request, work);
            var data = Data.builder().request(requestView(request)).build();
            String parent = approvalById.containsKey(request.getApprovalId()) ? "approval:" + request.getApprovalId() + ":decided"
                    : work != null ? "work:" + work.getTaskId() + ":proposed" : jobEntry(job);
            out.add(entry("request:" + request.getRequestId() + ":sent", Kind.REQUEST_SENT, request.getCreatedAt(), request.getRequesterUserId(), request.getQuestion(), job, names)
                    .links(requestLinks(request, job, work).parentEntryId(parent).build()).placement(display).data(data).build());
            if (request.getDecisionAt() != null) {
                boolean reject = request.getStatus() == CheckRequestStatus.REJECTED;
                FollowupDtos.SourceRef ref = reject ? new FollowupDtos.SourceRef("REQUEST_DECISION", request.getRequestId()) : null;
                out.add(entry("request:" + request.getRequestId() + ":decision", reject ? Kind.REQUEST_REJECTED : Kind.REQUEST_ACCEPTED,
                        request.getDecisionAt(), request.getOwnerUserId(), request.getDecisionComment(), job, names)
                        .links(requestLinks(request, job, work).parentEntryId("request:" + request.getRequestId() + ":sent").build()).placement(display)
                        .sourceRef(ref).analysisUse(ref == null ? null : usage(ref)).data(data).build());
            }
            if (request.getWithdrawnAt() != null)
                out.add(entry("request:" + request.getRequestId() + ":withdrawn", Kind.REQUEST_WITHDRAWN, request.getWithdrawnAt(), request.getWithdrawnBy(), request.getWithdrawReason(), job, names)
                        .links(requestLinks(request, job, work).parentEntryId("request:" + request.getRequestId() + ":sent").build()).placement(display).data(data).build());
        }
        for (Reply reply : replies) {
            CheckRequest request = requestById.get(reply.getRequestId()); Job job = request == null ? null : requestJob(request);
            WorkItem work = requestWork(reply.getRequestId()); var ref = new FollowupDtos.SourceRef("REPLY", reply.getReplyId());
            out.add(entry("reply:" + reply.getReplyId(), Kind.REPLY, reply.getCreatedAt(), reply.getAuthorUserId(), reply.getFindings(), job, names)
                    .links((request == null ? links(job).requestId(reply.getRequestId()) : requestLinks(request, job, work)).replyId(reply.getReplyId())
                            .parentEntryId(request == null ? null : "request:" + reply.getRequestId() + ":sent").build())
                    .placement(request == null ? null : requestPlacement(request, work))
                    .requestSummary(request == null ? null : new RequestSummary(shortText(request.getQuestion()), request.getTargetProcessCode(), request.getEquipmentId()))
                    .data(Data.builder().reply(CheckRequestDtos.ReplyView.from(reply)).build()).sourceRef(ref).analysisUse(usage(ref)).build());
        }
        for (IncidentAction action : actions) {
            WorkItem work = works.stream().filter(w -> action.getActionId().equals(w.getActionId())).findFirst().orElse(null);
            if (work == null && action.getRelatedSource() != null) work = workById.get(action.getRelatedSource().get("refId"));
            Job job = work == null ? approvalJob(approvalById.get(action.getApprovalId())) : workJob(work);
            Approval actionApproval = approvalById.get(action.getApprovalId());
            Placement display = work != null ? workPlacement(work) : actionApproval != null ? approvalPlacement(actionApproval, null)
                    : resourcePlacement(job, "action:" + action.getActionId(), action.getCreatedAt(), action.getDescription());
            var ref = new FollowupDtos.SourceRef("ACTION", action.getActionId());
            out.add(entry("action:" + action.getActionId(), Kind.ACTION_RESULT, action.getCreatedAt(), action.getPerformedBy(), action.getDescription(), job, names)
                    .links(links(job).actionId(action.getActionId()).taskId(work == null ? null : work.getTaskId()).approvalId(action.getApprovalId())
                            .sourceResultId(work != null ? source(work.getSourceResultId()) : actionApproval == null ? null : source(actionApproval.getSourceResultId()))
                            .parentEntryId(work == null ? jobEntry(job) : "work:" + work.getTaskId() + ":proposed").build())
                    .placement(display).sourceRef(ref).analysisUse(usage(ref)).data(Data.builder().action(ActionDtos.ActionView.from(action)).build()).build());
        }
        for (WorkItem work : works) {
            Job job = workJob(work);
            out.add(entry("work:" + work.getTaskId() + ":proposed", Kind.WORK_PROPOSED, work.getCreatedAt(), work.getCreatedBy(), work.getTitle(), job, names)
                    .links(links(job).taskId(work.getTaskId()).sourceResultId(work.getSourceResultId()).parentEntryId(
                            resultById.containsKey(work.getSourceResultId()) ? "result:" + work.getSourceResultId() : null).build())
                    .placement(workPlacement(work)).data(Data.builder().work(WorkDtos.View.from(work)).build()).build());
        }
        var timeline = incident.getTimeline();
        for (int i = 0; i < timeline.size(); i++) {
            TimelineEntry event = timeline.get(i);
            if (!Set.of("WORK_ADOPTED", "WORK_DECIDED", "WORK_CLOSED_UNPERFORMED").contains(event.getType()) || !"WORK_ITEM".equals(event.getRefType())) continue;
            WorkItem work = workById.get(event.getRefId()); if (work == null) continue;
            Job job = workJob(work);
            out.add(entry("timeline:" + incident.getIncidentId() + ":" + String.format("%010d", i), Kind.WORK_EVENT, event.getAt(), event.getActorId(), event.getMessage(), job, names)
                    .eventType(event.getType()).links(links(job).taskId(work.getTaskId()).sourceResultId(source(work.getSourceResultId()))
                            .parentEntryId("work:" + work.getTaskId() + ":proposed").build())
                    .placement(workPlacement(work)).data(Data.builder().work(WorkDtos.View.from(work)).build()).build());
        }
        return out;
    }

    List<SelectedInput> safeInputs(AnalysisRequest request) {
        AnalysisResult result = finalResult(request);
        return request.getInputs().stream().map(input -> new SelectedInput(source(input), str(input.get("evidenceId")), str(input.get("content")),
                str(input.get("authorUserId")), str(input.get("createdAt")), strings(input.get("evidenceIds")), true,
                result == null ? null : cited(result, str(input.get("evidenceId"))))).toList();
    }
    private static String str(Object value) { return value == null ? null : value.toString(); }
    private static List<String> strings(Object value) { return value instanceof List<?> list ? list.stream().filter(String.class::isInstance).map(String.class::cast).toList() : List.of(); }
    private static FollowupDtos.SourceRef source(Map<String, Object> input) {
        return input.get("sourceRef") instanceof Map<?, ?> ref ? new FollowupDtos.SourceRef(str(ref.get("type")), str(ref.get("id"))) : null;
    }
    private static boolean contains(Object value, String id) {
        if (value instanceof Map<?, ?> map) return map.values().stream().anyMatch(v -> contains(v, id));
        if (value instanceof Collection<?> collection) return collection.stream().anyMatch(v -> contains(v, id));
        return id != null && id.equals(value);
    }
    private static boolean cited(AnalysisResult result, String id) {
        return result.getEvidenceRefs().stream().anyMatch(r -> id != null && id.equals(r.get("evidenceId")))
                && (contains(result.getObservedFacts(), id) || contains(result.getCandidateCauses(), id));
    }
    private Map<String, Object> usage(FollowupDtos.SourceRef source) {
        var used = followups.stream().filter(r -> r.getInputs().stream().anyMatch(i -> source.equals(source(i)))).toList();
        Map<String, Object> use = new LinkedHashMap<>();
        use.put("includedCount", used.size());
        use.put("successfulCount", used.stream().filter(r -> current(r) != null && current(r).getStatus() == JobStatus.SUCCEEDED && finalResult(r) != null).count());
        use.put("activeRequestIds", used.stream().filter(r -> current(r) != null && current(r).getStatus() != JobStatus.SUCCEEDED && current(r).getStatus() != JobStatus.FAILED).map(AnalysisRequest::getAnalysisRequestId).toList());
        use.put("lastRequestId", used.isEmpty() ? null : used.getFirst().getAnalysisRequestId());
        use.put("lastStatus", used.isEmpty() || current(used.getFirst()) == null ? null : current(used.getFirst()).getStatus());
        return use;
    }
    List<FollowupDtos.Result> investigationResults() {
        List<FollowupDtos.Result> out = new ArrayList<>();
        for (Entry entry : entries(Map.of())) {
            if (entry.sourceRef() == null) continue;
            String task = entry.links().taskId(), request = entry.links().requestId();
            List<String> evidence = entry.data().reply() != null ? entry.data().reply().evidenceIds()
                    : entry.data().action() != null ? entry.data().action().evidenceIds() : entry.data().request().evidenceIds();
            out.add(new FollowupDtos.Result(entry.sourceRef(), incident.getIncidentId(), task, request, entry.text(), entry.actor().userId(), entry.occurredAt(), evidence, entry.analysisUse()));
        }
        return out.stream().sorted(Comparator.comparing(FollowupDtos.Result::createdAt).reversed().thenComparing(r -> r.sourceRef().type() + ":" + r.sourceRef().id())).toList();
    }
}
