package com.fabcontext.api.domain.conversation;

import java.time.Clock;
import java.time.Instant;
import java.util.*;

import com.fabcontext.api.auth.*;
import com.fabcontext.api.common.ApiException;
import com.fabcontext.api.domain.action.IncidentActionRepository;
import com.fabcontext.api.domain.analysis.AnalysisDtos.AnalysisResultView;
import com.fabcontext.api.domain.analysis.AnalysisResultRepository;
import com.fabcontext.api.domain.approval.ApprovalDtos.ApprovalView;
import com.fabcontext.api.domain.approval.ApprovalRepository;
import com.fabcontext.api.domain.approval.ApprovalStatus;
import com.fabcontext.api.domain.checkrequest.CheckRequestRepositories.*;
import com.fabcontext.api.domain.incident.IncidentService;
import com.fabcontext.api.domain.investigation.AnalysisRequestRepository;
import com.fabcontext.api.domain.job.*;
import com.fabcontext.api.domain.job.JobDtos.JobView;
import com.fabcontext.api.domain.work.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Service
@RequiredArgsConstructor
public class ConversationService {
    private final IncidentService incidents;
    private final JobRepository jobs;
    private final AnalysisResultRepository results;
    private final ApprovalRepository approvals;
    private final CheckRequestRepository requests;
    private final ReplyRepository replies;
    private final IncidentActionRepository actions;
    private final WorkItemRepository work;
    private final AnalysisRequestRepository followups;
    private final UserAccountRepository users;
    private final Clock clock;

    /** 여러 저장소를 같은 MVCC 스냅샷에서 읽는다. 조회 중 도착한 회신은 다음 조회에 나타난다. */
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public ConversationDtos.View get(String incidentId, AuthUser user, String cursor, Integer size) {
        var incident = incidents.loadForUser(incidentId, user);
        int limit = size == null ? 50 : size;
        if (limit < 1 || limit > 100) throw ApiException.badRequest("INVALID_PAGE_SIZE", "size는 1~100 사이여야 합니다.");
        var before = ConversationCursor.decode(cursor, incidentId);
        var allJobs = jobs.findByIncidentIdOrderByCreatedAtDesc(incidentId).stream()
                .filter(j -> List.of(JobKind.BASIC, JobKind.DEEP, JobKind.RESUME).contains(j.getKind())).toList();
        Set<String> investigationJobIds = new HashSet<>(); allJobs.forEach(j -> investigationJobIds.add(j.getJobId()));
        var assembly = new ConversationAssembler(incident, allJobs,
                results.findByIncidentIdOrderByCreatedAtDesc(incidentId).stream().filter(r -> investigationJobIds.contains(r.getJobId())).toList(),
                approvals.findByIncidentIdOrderByCreatedAtDesc(incidentId).stream().filter(a -> a.getActionType() != com.fabcontext.api.domain.approval.ApprovalActionType.CONFIRM_REPORT).toList(),
                requests.findByIncidentIdOrderByCreatedAtAsc(incidentId),
                replies.findByIncidentIdOrderByCreatedAtAsc(incidentId),
                actions.findConversationActions(incidentId), work.findByIncidentIdOrderByCreatedAtAsc(incidentId),
                followups.findByIncidentIdOrderByCreatedAtDesc(incidentId), user.userId());
        Map<String, String> names = new HashMap<>();
        users.findAllById(assembly.actorIds()).forEach(u -> names.put(u.getUserId(), u.getDisplayName()));
        var entries = assembly.entries(names).stream().sorted(Comparator.comparing(ConversationCursor::key, ConversationCursor.ORDER)).toList();
        var eligible = entries.stream().filter(e -> before == null || ConversationCursor.ORDER.compare(ConversationCursor.key(e), before) < 0).toList();
        int start = Math.max(0, eligible.size() - limit);
        var page = List.copyOf(eligible.subList(start, eligible.size()));
        String next = start > 0 ? ConversationCursor.encode(incidentId, ConversationCursor.key(page.getFirst())) : null;
        var latestCause = assembly.results.stream().filter(r -> "CAUSE_ANALYSIS".equals(r.getResultKind())).findFirst().orElse(null);
        log.debug("event=conversation.read incidentId={} entries={} total={} jobs={}", incidentId, page.size(), entries.size(), allJobs.size());
        return new ConversationDtos.View(incidentId, incident.getVersion(), Instant.now(clock), page, next,
                allJobs.stream().map(JobView::from).toList(),
                allJobs.stream().filter(j -> j.getStatus().isInProgress()).map(JobView::from).toList(),
                allJobs.stream().filter(j -> j.getStatus().isWaiting()).map(JobView::from).toList(),
                assembly.approvals.stream().filter(assembly::pendingCard).map(ApprovalView::from).toList(),
                assembly.works.stream().filter(w -> w.getStatus() == WorkItem.Status.PROPOSED || w.getStatus() == WorkItem.Status.WAITING_MANAGER).map(WorkDtos.View::from).toList(),
                latestCause == null ? null : AnalysisResultView.from(latestCause, null), assembly.investigationResults());
    }
}
