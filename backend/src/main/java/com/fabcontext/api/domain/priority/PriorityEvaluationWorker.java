package com.fabcontext.api.domain.priority;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.TransientDataAccessException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** 접수된 평가를 AI와 독립 실행한다. 스케줄러는 사용자 알림 조회용 폴링이 아닌 서버 내부 작업 처리다. */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "fab.priority.worker-enabled", havingValue = "true", matchIfMissing = true)
public class PriorityEvaluationWorker {
    private final PriorityEvaluationService service;
    private final PriorityEvaluationCalculator calculator;

    @Scheduled(fixedDelayString = "${fab.priority.poll-delay-ms:1000}")
    public void tick() {
        for (int n = 0; n < 5; n++) {
            var claimed = service.claimNext();
            if (claimed.isEmpty()) return;
            var input = claimed.get();
            try {
                service.complete(input, calculator.calculate(input));
            } catch (TransientDataAccessException | DataAccessResourceFailureException ex) {
                log.warn("event=priority.retry incidentId={} attempt={} errorType={}", input.incidentId(), input.attempt(), ex.getClass().getSimpleName());
                service.fail(input, "DATA_ACCESS_UNAVAILABLE", true);
            } catch (RuntimeException ex) {
                log.error("event=priority.error incidentId={} attempt={} errorType={}", input.incidentId(), input.attempt(), ex.getClass().getSimpleName());
                service.fail(input, "EVALUATION_ERROR", false);
            }
        }
    }
}
