package com.fabcontext.api.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasItem;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import com.fabcontext.api.common.ApiException;
import com.fabcontext.api.common.Ids;
import com.fabcontext.api.domain.incident.Incident;
import com.fabcontext.api.domain.incident.IncidentRepository;
import com.fabcontext.api.domain.job.Job;
import com.fabcontext.api.domain.job.JobKind;
import com.fabcontext.api.domain.job.JobRepository;
import com.fabcontext.api.domain.report.ReportService;
import com.fabcontext.api.support.ApiTestSupport;
import org.springframework.beans.factory.annotation.Autowired;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("003 보고서 확정 = 사건 종료 · 사건당 확정 1회 · 종료 사건 쓰기 거부")
class ReportConfirmCloseApiTest extends ApiTestSupport {

	@Autowired
	JobRepository jobRepository;

	@Autowired
	IncidentRepository incidentRepository;

	@Autowired
	ReportService reportService;

	private String draft(Session s, String incidentId, Integer base) throws Exception {
		String body = "{\"expectedIncidentVersion\":" + incidentVersion(s, incidentId)
				+ (base == null ? "" : ",\"baseReportVersion\":" + base) + "}";
		String jobId = json(postAs(s, "/api/incidents/{id}/report-jobs", body, incidentId)
			.andExpect(status().isAccepted()), "$.data.jobId");
		awaitJob(s, jobId, "SUCCEEDED");
		return json(getAs(s, "/api/jobs/{id}", jobId), "$.data.resultRef.id");
	}

	private static String confirmBody(long revision) {
		return "{\"expectedContentRevision\":" + revision + ",\"reviewConfirmed\":true,\"classification\":{\"category\":\"METHOD\"}}";
	}

	@Test
	void confirmingClosesTheIncidentAndBlocksFurtherChanges() throws Exception {
		Session engineer = cmp();
		String incidentId = newIncident(engineer, false).incidentId();
		String reportId = draft(engineer, incidentId, null);

		postAs(engineer, "/api/reports/{id}/confirmations", confirmBody(1), reportId).andExpect(status().isOk())
			.andExpect(jsonPath("$.data.status").value("CONFIRMED"));
		getAs(engineer, "/api/incidents/{id}", incidentId)
			.andExpect(jsonPath("$.data.status").value("CLOSED"))
			.andExpect(jsonPath("$.data.timeline[*].type", hasItem("REPORT_CONFIRMED")))
			.andExpect(jsonPath("$.data.timeline[*].message", hasItem(containsString("보고서 v1 확정으로 사건 종료"))));

		long version = incidentVersion(engineer, incidentId);
		postAs(engineer, "/api/incidents/{id}/report-jobs", "{\"expectedIncidentVersion\":" + version + "}", incidentId)
			.andExpect(status().isConflict()).andExpect(jsonPath("$.error.code").value("INCIDENT_CLOSED"));
		postAs(engineer, "/api/incidents/{id}/questions",
				"{\"question\":\"추가로 볼 것이 있나요?\",\"expectedIncidentVersion\":" + version + "}", incidentId)
			.andExpect(status().isConflict()).andExpect(jsonPath("$.error.code").value("INCIDENT_CLOSED"));
		// 조회 · 내려받기는 그대로
		getAs(engineer, "/api/reports/{id}", reportId).andExpect(status().isOk());
		getAs(engineer, "/api/reports/{id}/export", reportId).andExpect(status().isOk());
	}

	@Test
	void onlyTheLatestDraftCanBeConfirmedOncePerIncident() throws Exception {
		Session engineer = cmp();
		String incidentId = newIncident(engineer, false).incidentId();
		String v1 = draft(engineer, incidentId, null);
		String v2 = draft(engineer, incidentId, 1);
		getAs(engineer, "/api/reports/{id}", v2).andExpect(jsonPath("$.data.version").value(2))
			.andExpect(jsonPath("$.data.baseReportVersion").value(1))
			.andExpect(jsonPath("$.data.sections[0].body").value(containsString("이전 보고서 v1")));

		postAs(engineer, "/api/reports/{id}/confirmations", confirmBody(1), v1)
			.andExpect(status().isConflict()).andExpect(jsonPath("$.error.code").value("NOT_LATEST_DRAFT"))
			.andExpect(jsonPath("$.error.details.latestReportId").value(v2));
		postAs(engineer, "/api/reports/{id}/confirmations", confirmBody(1), v2).andExpect(status().isOk());
		// 남은 옛 초안은 확정할 수 없고, 이미 확정된 보고서를 알려 준다
		postAs(engineer, "/api/reports/{id}/confirmations", confirmBody(1), v1)
			.andExpect(status().isConflict()).andExpect(jsonPath("$.error.code").value("REPORT_ALREADY_CONFIRMED"))
			.andExpect(jsonPath("$.error.details.reportId").value(v2));
		// 옛 초안 편집도 종료 사건이라 거부
		String sections = "[{\"key\":\"overview\",\"title\":\"사건 개요\",\"body\":\"a\"},{\"key\":\"investigation\",\"title\":\"조사 내용\",\"body\":\"b\"},"
				+ "{\"key\":\"requestsAndReplies\",\"title\":\"요청과 회신\",\"body\":\"c\"},{\"key\":\"actionsAndVerification\",\"title\":\"조치와 검증\",\"body\":\"d\"},"
				+ "{\"key\":\"unresolved\",\"title\":\"미해결\",\"body\":\"e\"}]";
		patchAs(engineer, "/api/reports/{id}", "{\"sections\":" + sections + ",\"expectedContentRevision\":1}", v1)
			.andExpect(status().isConflict()).andExpect(jsonPath("$.error.code").value("INCIDENT_CLOSED"));
	}

	@Test
	void concurrentConfirmationsSucceedExactlyOnce() throws Exception {
		Session engineer = cmp();
		String incidentId = newIncident(engineer, false).incidentId();
		String reportId = draft(engineer, incidentId, null);

		int requests = 10;
		CountDownLatch start = new CountDownLatch(1);
		ExecutorService pool = Executors.newFixedThreadPool(requests);
		try {
			List<Future<Integer>> results = new ArrayList<>();
			for (int i = 0; i < requests; i++) {
				Callable<Integer> confirm = () -> {
					start.await();
					return postAs(engineer, "/api/reports/{id}/confirmations", confirmBody(1), reportId).andReturn()
						.getResponse().getStatus();
				};
				results.add(pool.submit(confirm));
			}
			start.countDown();
			List<Integer> statuses = new ArrayList<>();
			for (Future<Integer> result : results) {
				statuses.add(result.get());
			}
			assertThat(statuses).filteredOn(code -> code == 200).hasSize(1);
			assertThat(statuses).filteredOn(code -> code != 200).allMatch(code -> code == 409);
		}
		finally {
			pool.shutdownNow();
		}
		getAs(engineer, "/api/incidents/{id}/reports", incidentId)
			.andExpect(jsonPath("$.data.items[0].status").value("CONFIRMED"));
		getAs(engineer, "/api/incidents/{id}", incidentId).andExpect(jsonPath("$.data.status").value("CLOSED"));
	}

	@Test
	void runningReportDraftBlocksConfirmationAndLateDraftsAreNotSaved() throws Exception {
		Session engineer = cmp();
		String incidentId = newIncident(engineer, false).incidentId();
		String reportId = draft(engineer, incidentId, null);
		// 초안 작업이 아직 대기 중(작업 전달 전) — 시뮬레이터가 가져가지 않도록 직접 저장한다
		Job pending = jobRepository.save(Job.builder().jobId(Ids.next("job")).incidentId(incidentId).kind(JobKind.REPORT)
			.attempt(1).threadId(Ids.next("thr")).requestedBy("USR-CMP-01").createdAt(java.time.Instant.now()).build());
		postAs(engineer, "/api/reports/{id}/confirmations", confirmBody(1), reportId)
			.andExpect(status().isConflict())
			.andExpect(jsonPath("$.error.details.blockers[0].kind").value("REPORT_IN_PROGRESS"))
			.andExpect(jsonPath("$.error.details.blockers[0].id").value(pending.getJobId()));

		jobRepository.delete(pending);
		postAs(engineer, "/api/reports/{id}/confirmations", confirmBody(1), reportId).andExpect(status().isOk());
		// 확정 뒤 늦게 도착한 초안 결과 — 종료된 사건에 새 판본을 만들지 않는다
		Incident closed = incidentRepository.findById(incidentId).orElseThrow();
		Job late = Job.builder().jobId(Ids.next("job")).incidentId(incidentId).kind(JobKind.REPORT).attempt(1)
			.threadId(Ids.next("thr")).requestedBy("USR-CMP-01").createdAt(java.time.Instant.now()).build();
		assertThatThrownBy(() -> reportService.createDraft(closed, late))
			.isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getCode()).isEqualTo("INCIDENT_CLOSED"));
		getAs(engineer, "/api/incidents/{id}/reports", incidentId)
			.andExpect(jsonPath("$.data.items", org.hamcrest.Matchers.hasSize(1)));
	}

	@Test
	void failedConfirmationLeavesReportAndIncidentUntouched() throws Exception {
		Session engineer = cmp();
		String incidentId = newIncident(engineer, false).incidentId();
		String status = json(getAs(engineer, "/api/incidents/{id}", incidentId), "$.data.status");
		String reportId = draft(engineer, incidentId, null);

		postAs(engineer, "/api/reports/{id}/confirmations",
				"{\"expectedContentRevision\":1,\"reviewConfirmed\":true,\"classification\":{\"category\":\"MACHINE\"}}", reportId)
			.andExpect(status().isUnprocessableEntity());
		getAs(engineer, "/api/reports/{id}", reportId).andExpect(jsonPath("$.data.status").value("DRAFT"))
			.andExpect(jsonPath("$.data.confirmedAt").doesNotExist());
		getAs(engineer, "/api/incidents/{id}", incidentId).andExpect(jsonPath("$.data.status").value(status));
	}
}
