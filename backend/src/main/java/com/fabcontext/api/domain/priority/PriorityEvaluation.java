package com.fabcontext.api.domain.priority;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.fabcontext.api.domain.incident.Incident;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/** 사건당 최초 평가 하나. AI Job과 별도이며 입력·정책·실패/재시도·계산 근거를 보존한다. */
@Entity
@Table(name = "priority_evaluation", indexes = @Index(columnList = "status,nextAttemptAt"))
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class PriorityEvaluation {
    public enum Status { PENDING, RUNNING, COMPLETED, FAILED }
    static final int MAX_ATTEMPTS = 3;
    @Id private String incidentId;
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "incidentId", insertable = false, updatable = false,
            foreignKey = @ForeignKey(name = "fk_priority_evaluation_incident"))
    @Getter(AccessLevel.NONE) private Incident incident;
    private String policyVersion;
    private Instant assessmentAsOf;
    @JdbcTypeCode(SqlTypes.JSON) private List<String> runIds = new ArrayList<>();
    @Enumerated(EnumType.STRING) private Status status;
    private int attempt;
    private Instant createdAt;
    private Instant nextAttemptAt;
    private Instant leaseUntil;
    private Instant finishedAt;
    private String errorCode;
    @JdbcTypeCode(SqlTypes.JSON) private Map<String, Object> details = new LinkedHashMap<>();
    @Version private long rowVersion;

    PriorityEvaluation(String incidentId, String policyVersion, Instant asOf, List<String> runIds, Instant now) {
        this.incidentId = incidentId;
        this.policyVersion = policyVersion;
        this.assessmentAsOf = asOf;
        this.runIds = List.copyOf(runIds);
        this.createdAt = now;
        this.nextAttemptAt = now;
        this.status = Status.PENDING;
    }

    void claim(Instant now) {
        attempt++;
        status = Status.RUNNING;
        leaseUntil = now.plusSeconds(120);
    }
    boolean matches(int expectedAttempt) { return status == Status.RUNNING && attempt == expectedAttempt; }
    void complete(Map<String, Object> details, Instant now) {
        status = Status.COMPLETED;
        this.details = details;
        errorCode = null;
        finishedAt = now;
        leaseUntil = null;
    }
    void fail(String code, Map<String, Object> details, boolean retryable, Instant now) {
        errorCode = code;
        this.details = details;
        leaseUntil = null;
        if (retryable && attempt < MAX_ATTEMPTS) {
            status = Status.PENDING;
            nextAttemptAt = now.plusSeconds(attempt * 2L);
        } else {
            status = Status.FAILED;
            finishedAt = now;
        }
    }
}
