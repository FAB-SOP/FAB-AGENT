package com.fabcontext.api.internal;

import static org.assertj.core.api.Assertions.assertThat;
import java.util.*;
import com.fabcontext.api.domain.job.Job;
import org.junit.jupiter.api.Test;

class AgentEventFingerprintTest {
    private final Job job = Job.builder().jobId("job_1").incidentId("incident_1").threadId("thread_1").build();
    private Map<String, Object> event(String type, Map<String, Object> payload) {
        return new LinkedHashMap<>(Map.of("eventId", "evt_1", "attempt", 1, "type", type, "payload", payload));
    }
    @Test void canonicalizationSortsNestedKeysAndNormalizesNumbersButPreservesArrayOrder() {
        var first = event("RESULT_READY", Map.of("result", Map.of("b", 1, "a", List.of("first", "second"))));
        var copy = event("RESULT_READY", Map.of("result", Map.of("a", List.of("first", "second"), "b", 1.0)));
        assertThat(AgentEventFingerprint.of(job, first)).isEqualTo(AgentEventFingerprint.of(job, copy));
        copy.put("payload", Map.of("result", Map.of("a", List.of("second", "first"), "b", 1)));
        assertThat(AgentEventFingerprint.of(job, first)).isNotEqualTo(AgentEventFingerprint.of(job, copy));
    }
    @Test void onlyTransportFieldsAndJobStartedTimestampAreIgnored() {
        var first = event("JOB_STARTED", Map.of("startedAt", "old"));
        var copy = event("JOB_STARTED", Map.of("startedAt", "new"));
        copy.putAll(Map.of("eventId", "another", "at", "later", "sequence", 5, "metrics", Map.of("calls", 5), "traceId", "later"));
        assertThat(AgentEventFingerprint.of(job, first)).isEqualTo(AgentEventFingerprint.of(job, copy));
        first = event("SUMMARY_READY", Map.of("snapshotRef", "s1", "summary", Map.of("createdAt", "old")));
        copy = event("SUMMARY_READY", Map.of("snapshotRef", "s1", "summary", Map.of("createdAt", "new")));
        assertThat(AgentEventFingerprint.of(job, first)).isNotEqualTo(AgentEventFingerprint.of(job, copy));
        copy = event("SUMMARY_READY", Map.of("snapshotRef", "s2", "summary", Map.of("createdAt", "old")));
        assertThat(AgentEventFingerprint.of(job, first)).isNotEqualTo(AgentEventFingerprint.of(job, copy));
    }
    @Test void retryTimestampIsIgnoredButAttemptErrorAndOtherEventPayloadsRemainContent() {
        var first = event("RETRY_SCHEDULED", Map.of("nextAttempt", 2, "errorCode", "TRANSIENT", "nextRetryAt", "old"));
        var repeated = event("RETRY_SCHEDULED", Map.of("nextAttempt", 2, "errorCode", "TRANSIENT", "nextRetryAt", "new"));
        assertThat(AgentEventFingerprint.of(job, first)).startsWith("v2:sha256:")
                .isEqualTo(AgentEventFingerprint.of(job, repeated));
        repeated.put("payload", Map.of("nextAttempt", 3, "errorCode", "TRANSIENT", "nextRetryAt", "new"));
        assertThat(AgentEventFingerprint.of(job, first)).isNotEqualTo(AgentEventFingerprint.of(job, repeated));
        repeated.put("payload", Map.of("nextAttempt", 2, "errorCode", "OTHER", "nextRetryAt", "new"));
        assertThat(AgentEventFingerprint.of(job, first)).isNotEqualTo(AgentEventFingerprint.of(job, repeated));
        assertThat(AgentEventFingerprint.of(job, event("JOB_FAILED", Map.of("message", "first"))))
                .isNotEqualTo(AgentEventFingerprint.of(job, event("JOB_FAILED", Map.of("message", "changed"))));
        assertThat(AgentEventFingerprint.of(job, event("ANALYSIS_PROGRESS", Map.of("stepCode", "START"))))
                .isNotEqualTo(AgentEventFingerprint.of(job, event("ANALYSIS_PROGRESS", Map.of("stepCode", "CHECK"))));
    }

}
