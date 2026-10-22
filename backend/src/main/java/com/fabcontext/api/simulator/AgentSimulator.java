package com.fabcontext.api.simulator;

import java.time.Instant;

import com.fabcontext.api.config.FabProperties;
import com.fabcontext.api.domain.job.JobDtos.JobQueuedEvent;
import com.fabcontext.api.domain.job.JobDtos;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionalEventListener;

/** 커밋된 QUEUED 작업을 일정 지연 후 RUNNING → 완료로 진행시킨다. */
@Slf4j
@Component
@RequiredArgsConstructor
public class AgentSimulator {

	private final TaskScheduler scheduler;
	private final AgentWorker worker;
	private final FabProperties properties;

	@TransactionalEventListener(fallbackExecution = true)
	public void onQueued(JobQueuedEvent event) {
		if (properties.agent().external()) {
			return; // fab-agent Worker가 GET /internal/jobs?status=QUEUED로 가져간다
		}
		schedule(() -> start(event.jobId()));
	}

	private void start(String jobId) {
		try {
			if (worker.start(jobId)) {
				schedule(() -> complete(jobId));
			}
		}
		catch (RuntimeException e) {
			log.warn("job {} start failed", jobId, e);
			worker.fail(jobId, "AGENT_ERROR", "작업 시작 실패: " + e.getMessage());
		}
	}

	private void complete(String jobId) {
		try {
			worker.complete(jobId);
		}
		catch (RuntimeException e) {
			log.warn("job {} failed", jobId, e);
			worker.fail(jobId, "AGENT_ERROR", "분석 처리 중 오류: " + e.getMessage());
		}
	}

	private void schedule(Runnable task) {
		scheduler.schedule(task, Instant.now().plusMillis(properties.agent().stepDelayMs()));
	}
}
