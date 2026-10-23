package com.fabcontext.api.domain.job;

import com.fabcontext.api.domain.incident.ActiveJobLookup;
import com.fabcontext.api.domain.incident.IncidentDtos.ActiveJob;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/** {@link ActiveJobLookup} 구현 — JobService와 따로 둔다(JobService는 IncidentService를 쓰므로 거기 두면 빈이 서로를 부른다) */
@Component
@RequiredArgsConstructor
public class ActiveJobs implements ActiveJobLookup {

	private final JobRepository jobRepository;

	@Override
	public ActiveJob find(String jobId) {
		return jobId == null ? null : jobRepository.findById(jobId).map(ActiveJobs::view).orElse(null);
	}

	@Override
	public java.util.List<ActiveJob> waitingForIncident(String incidentId) {
		return jobRepository.findByIncidentIdOrderByCreatedAtDesc(incidentId).stream()
			.filter(j -> j.getKind().affectsInvestigationState() && j.getStatus().isWaiting()).map(ActiveJobs::view).toList();
	}

	static ActiveJob view(Job job) {
		return new ActiveJob(job.getJobId(), job.getKind().name(), job.getStatus().name(), job.getAttempt(), job.waitingRef());
	}
}
