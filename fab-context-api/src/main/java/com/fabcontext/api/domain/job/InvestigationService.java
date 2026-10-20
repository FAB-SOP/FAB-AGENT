package com.fabcontext.api.domain.job;

import java.time.Clock;
import java.time.Instant;
import java.util.Map;

import com.fabcontext.api.auth.AuthUser;
import com.fabcontext.api.domain.incident.Incident;
import com.fabcontext.api.domain.incident.IncidentService;
import com.fabcontext.api.domain.job.JobDtos.Accepted;
import com.fabcontext.api.domain.job.JobDtos.BasicAnalysisRequest;
import com.fabcontext.api.domain.job.JobDtos.Query;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class InvestigationService {

	private final IncidentService incidentService;
	private final JobService jobService;
	private final Clock clock;

	@Transactional
	public Accepted ask(String incidentId, AuthUser user, Query body) {
		Incident incident = incidentService.loadForUser(incidentId, user);
		incident.requireNotClosed();
		incident.checkVersion(body.expectedIncidentVersion());
		jobService.assertNoRunningJob(incident);
		String scope = body.scope() == null ? "AUTO" : body.scope();
		Job job = jobService.enqueue(incident, JobKind.DEEP, user.userId(), body.question(), scope, Map.of(), null, null);
		incident.record(Instant.now(clock), "QUESTION_ASKED", user.userId(), "추가 조사 질문: " + body.question(), "JOB",
			job.getJobId());
		return Accepted.of(job, incident.getDisplayId(), false);
	}

	@Transactional
	public Accepted rerunBasic(String incidentId, AuthUser user, BasicAnalysisRequest body) {
		Incident incident = incidentService.loadForUser(incidentId, user);
		incident.requireNotClosed();
		incident.checkVersion(body.expectedIncidentVersion());
		jobService.assertNoRunningJob(incident);
		Job job = jobService.enqueue(incident, JobKind.BASIC, user.userId(), null, null,
			body.reason() == null ? Map.of() : Map.of("reason", body.reason()), null, null);
		incident.record(Instant.now(clock), "ANALYSIS_RERUN", user.userId(),
			"기본 분석 재실행 (시도 " + job.getAttempt() + ")" + (body.reason() == null ? "" : ": " + body.reason()), "JOB",
			job.getJobId());
		return Accepted.of(job, incident.getDisplayId(), false);
	}
}
