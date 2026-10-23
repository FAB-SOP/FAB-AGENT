package com.fabcontext.api.internal;

import java.time.Clock;
import java.time.Instant;
import java.util.*;

import com.fabcontext.api.common.AfterCommit;
import com.fabcontext.api.common.Hashing;
import com.fabcontext.api.domain.job.*;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/** 호출자는 사건 → 작업 잠금을 가진 동일 트랜잭션에서 검사·수신 기록·업무 변경을 함께 커밋한다. */
@Slf4j
@Component
@RequiredArgsConstructor
class AgentEventDeduplication {
    private static final Set<String> FINAL_TYPES = Set.of("RESULT_READY", "REPORT_DRAFT_READY", "SUMMARY_READY", "JOB_FAILED");
    private final ProcessedAgentEventRepository receipts;
    private final EntityManager em;
    private final JdbcTemplate jdbc;
    private final Clock clock;

    Map<String, Object> check(Job job, String eventId, int attempt, String type, String hash) {
        // 서로 다른 사건이 같은 전역 eventId를 동시에 쓰는 오류도 unique 위반/500 대신 충돌로 처리한다.
        // 해시 충돌은 두 요청을 잠깐 직렬화할 뿐이며, 실제 동일성은 원 eventId와 내용 해시로 검사한다.
        long lock = Long.parseUnsignedLong(Hashing.sha256("agent-event:" + eventId).substring(0, 16), 16);
        jdbc.execute((ConnectionCallback<Void>) connection -> {
            try (var statement = connection.prepareStatement("select pg_advisory_xact_lock(?)")) {
                statement.setLong(1, lock); statement.execute();
            }
            return null;
        });
        var receipt = receipts.findById(eventId).orElse(null);
        if (receipt != null) return compare(job, receipt, eventId, attempt, type, hash, false);
        // 지난 attempt는 새 논리 이벤트로 비교하지 않는다. 기존 ID 재전송은 위에서 확인한다.
        if (attempt != job.getAttempt()) return null;
        if (FINAL_TYPES.contains(type) || Set.of("APPROVAL_REQUIRED", "WAITING_REPLY").contains(type)) {
            Set<String> types = FINAL_TYPES.contains(type) ? FINAL_TYPES : Set.of(type);
            var finals = receipts.findByJobIdAndAttemptAndEventTypeIn(job.getJobId(), attempt, types);
            if (!finals.isEmpty()) return compare(job, finals.getFirst(), eventId, attempt, type, hash, true);
        }
        return null;
    }

    private Map<String, Object> compare(Job job, ProcessedAgentEvent receipt, String eventId, int attempt,
            String type, String hash, boolean logical) {
        em.refresh(receipt, LockModeType.PESSIMISTIC_WRITE);
        boolean sameScope = receipt.getJobId().equals(job.getJobId()) && receipt.getAttempt() == attempt && receipt.getEventType().equals(type);
        if (sameScope && receipt.getContentHash() == null) {
            // 기존 행을 새 요청의 내용으로 채우면 과거 결과를 잘못 확정하므로 해시를 사후 기록하지 않는다.
            AfterCommit.run(() -> log.warn("event=agent.event.legacy-unverifiable jobId={} eventId={}", job.getJobId(), eventId));
            return ignored(job, logical ? "JOB_FINISHED" : "DUPLICATE_EVENT");
        }
        if (sameScope && hash.equals(receipt.getContentHash()))
            return ignored(job, logical ? "DUPLICATE_RESULT" : "DUPLICATE_EVENT");
        receipt.recordConflict(eventId, hash, Instant.now(clock));
        AfterCommit.run(() -> log.warn("event=agent.event.conflict jobId={} eventId={} acceptedEventId={} attempt={} type={} contentHash={}",
                job.getJobId(), eventId, receipt.getEventId(), attempt, type, hash));
        return ignored(job, "EVENT_CONTENT_CONFLICT");
    }
    private Map<String, Object> ignored(Job job, String reason) {
        return Map.of("jobId", job.getJobId(), "applied", false, "reason", reason);
    }
}
