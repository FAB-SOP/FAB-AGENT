package com.fabcontext.api.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.Map;

import com.fabcontext.api.support.ApiTestSupport;
import org.junit.jupiter.api.Test;

/** 실제 업무 흐름에서 알림이 리소스 관계로 정한 수신자에게만 저장되는지 확인한다 */
class NotificationIntegrationApiTest extends ApiTestSupport {

	private String propose(Session s, String id, String operation, String title) throws Exception {
		String result = json(getAs(s, "/api/incidents/{id}/analysis-results", id), "$.data.resultId");
		return json(postAs(s, "/api/incidents/{id}/work-items", "{\"operationCode\":\"" + operation + "\",\"title\":\""
			+ title + "\",\"reason\":\"분석 결과에서 필요한 후속 확인\",\"sourceResultId\":\"" + result
			+ "\",\"expectedIncidentVersion\":" + incidentVersion(s, id) + "}", id).andExpect(status().isCreated()),
			"$.data.taskId");
	}

	/** 그 사용자의 알림 중 대상 ID가 일치하는 것 */
	private List<Map<String, Object>> notificationsFor(Session s, String targetId) throws Exception {
		return json(getAs(s, "/api/notifications?size=100"), "$.data.items[?(@.target.id=='" + targetId + "')]");
	}

	@Test
	void newIncidentNotifiesProcessOwnerOnlyAndBasicAnalysisByServiceNotifiesNobody() throws Exception {
		Session cmp = cmp();
		NewIncident created = newIncident(cmp, false);
		String incidentId = created.incidentId();
		List<Map<String, Object>> mine = notificationsFor(cmp, incidentId);
		assertThat(mine).extracting(n -> n.get("type")).containsExactly("INCIDENT_CREATED");
		assertThat(mine.get(0).get("incidentDisplayId")).isNotNull();
		// 다른 공정 · 정비팀 · (공정 담당자가 있으므로) 관리자는 받지 않는다
		assertThat(notificationsFor(cvd(), incidentId)).isEmpty();
		assertThat(notificationsFor(login("mnt.jung"), incidentId)).isEmpty();
		assertThat(notificationsFor(manager(), incidentId)).isEmpty();
		// 사건 접수 BASIC은 svc_simulator 요청 — 사람 수신자가 없다
		assertThat(notificationsFor(cmp, created.jobId())).isEmpty();
	}

	@Test
	void teamRequestNotifiesOwnerAndReplyNotifiesRequesterWithoutGrantingIncidentAccess() throws Exception {
		Session cmp = cmp();
		String incidentId = newIncident(cmp, false).incidentId();
		String task = propose(cmp, incidentId, "UPSTREAM_CHECK", "앞 공정 계측 결과 확인 알림");
		String request = json(postAs(cmp, "/api/work-items/{id}/adoptions",
			"{\"expectedVersion\":1,\"ownerUserId\":\"USR-ETCH-01\",\"targetProcessCode\":\"ETCH\"}", task)
			.andExpect(status().isOk()), "$.data.requestId");
		Session etch = login("etch.lee");
		List<Map<String, Object>> received = notificationsFor(etch, request);
		assertThat(received).extracting(n -> n.get("type")).containsExactly("CHECK_REQUEST_RECEIVED");
		assertThat(received.get(0).get("actorUserId")).isEqualTo("USR-CMP-01");
		// 자기 행동(요청 발송 · 자기 승인)은 본인에게 알리지 않는다
		assertThat(notificationsFor(cmp, request)).isEmpty();
		// 알림이 사건 접근 권한을 주지 않는다 — 요청 화면만 열린다
		getAs(etch, "/api/check-requests/{id}", request).andExpect(status().isOk());
		getAs(etch, "/api/incidents/{id}", incidentId).andExpect(status().isForbidden());

		acceptRequest(etch,request);
		String reply = json(postAs(etch, "/api/check-requests/{id}/replies",
			"{\"findings\":\"처리 이력 확인 완료 — 비공개 메모\",\"confirmedByUser\":true,\"expectedVersion\":2}", request)
			.andExpect(status().isCreated()), "$.data.replyId");
		List<Map<String, Object>> replied = notificationsFor(cmp, reply);
		assertThat(replied).extracting(n -> n.get("type")).containsExactly("CHECK_REQUEST_REPLIED");
		assertThat(replied.get(0).get("parent")).isEqualTo(Map.of("type", "CHECK_REQUEST", "id", request));
		// 회신 내용(자유 입력)은 알림에 담지 않는다
		assertThat(getAs(cmp, "/api/notifications?size=100").andReturn().getResponse()
			.getContentAsString(java.nio.charset.StandardCharsets.UTF_8)).doesNotContain("비공개 메모");
		assertThat(notificationsFor(etch, reply)).isEmpty();
	}

	@Test
	void managerApprovalRequestAndDecisionNotifyApproverThenRequesterAndReadingChangesNothing() throws Exception {
		Session cmp = cmp();
		String incidentId = newIncident(cmp, false).incidentId();
		String task = propose(cmp, incidentId, "CHANGE_SETPOINT", "압력 설정값 변경 계획 검토 알림");
		long version = number(postAs(cmp, "/api/work-items/{id}/adoptions", "{\"expectedVersion\":1}", task)
			.andExpect(jsonPath("$.data.status").value("WAITING_MANAGER")), "$.data.version");
		Session manager = manager();
		List<Map<String, Object>> asked = notificationsFor(manager, task);
		assertThat(asked).extracting(n -> n.get("type")).containsExactly("WORK_APPROVAL_REQUESTED");

		// 읽음은 업무 상태를 바꾸지 않는다
		postAs(manager, "/api/notifications/{id}/read", "", asked.get(0).get("notificationId")).andExpect(status().isOk());
		getAs(cmp, "/api/incidents/{id}/work-items", incidentId)
			.andExpect(jsonPath("$.data.items[0].status").value("WAITING_MANAGER"))
			.andExpect(jsonPath("$.data.items[0].version").value(version));

		postAs(manager, "/api/work-items/{id}/decisions",
			"{\"decision\":\"REJECT\",\"comment\":\"추가 근거 필요\",\"expectedVersion\":" + version + "}", task)
			.andExpect(status().isOk());
		assertThat(notificationsFor(cmp, task)).extracting(n -> n.get("type")).containsExactly("WORK_REJECTED");
		assertThat(notificationsFor(manager, task)).hasSize(1);
	}

	@Test
	void userRequestedAnalysisNotifiesRequesterOnSuccessAndFailure() throws Exception {
		Session cmp = cmp();
		String incidentId = newIncident(cmp, false).incidentId();
		String ok = json(postAs(cmp, "/api/incidents/{id}/basic-analysis-jobs",
			"{\"expectedIncidentVersion\":" + incidentVersion(cmp, incidentId) + "}", incidentId)
			.andExpect(status().isAccepted()), "$.data.jobId");
		awaitJob(cmp, ok, "SUCCEEDED");
		awaitNotification(cmp, ok, "ANALYSIS_SUCCEEDED");
		String failed = json(postAs(cmp, "/api/incidents/{id}/questions",
			"{\"question\":\"[fail] 실패 확인\",\"expectedIncidentVersion\":" + incidentVersion(cmp, incidentId) + "}",
			incidentId).andExpect(status().isAccepted()), "$.data.jobId");
		awaitJob(cmp, failed, "FAILED");
		awaitNotification(cmp, failed, "ANALYSIS_FAILED");
		assertThat(notificationsFor(cvd(), failed)).isEmpty();
	}


	@org.springframework.beans.factory.annotation.Autowired
	com.fabcontext.api.domain.job.JobRepository jobs;

	@Test
	void requestDecisionsNotifySenderOnceAndDoNotRunAnalysis() throws Exception {
		Session sender=cmp(), recipient=login("etch.lee");
		for(boolean accept:List.of(true,false)) {
			String incidentId=newIncident(sender,false).incidentId();
			String task=propose(sender,incidentId,"UPSTREAM_CHECK","앞 공정 확인 요청 결정 알림");
			String requestId=json(postAs(sender,"/api/work-items/{id}/adoptions",
				"{\"expectedVersion\":1,\"ownerUserId\":\"USR-ETCH-01\",\"targetProcessCode\":\"ETCH\"}",task),"$.data.requestId");
			long before=jobs.findByIncidentIdOrderByCreatedAtDesc(incidentId).size();
			if(!accept) {
				postAs(recipient,"/api/check-requests/{id}/decisions","{\"decision\":\"REJECT\",\"expectedVersion\":1}",requestId)
					.andExpect(status().isUnprocessableEntity());
				assertThat(notificationsFor(sender,requestId)).isEmpty();
			}
			String body="{\"decision\":\""+(accept?"ACCEPT":"REJECT")+"\",\"comment\":\"담당 범위 확인\",\"expectedVersion\":1}";
			String key=java.util.UUID.randomUUID().toString();
			postWithKey(recipient,key,"/api/check-requests/{id}/decisions",body,requestId).andExpect(status().isOk());
			postWithKey(recipient,key,"/api/check-requests/{id}/decisions",body,requestId).andExpect(status().isOk());
			assertThat(notificationsFor(sender,requestId)).extracting(n->n.get("type"))
				.containsExactly(accept?"CHECK_REQUEST_ACCEPTED":"CHECK_REQUEST_REJECTED");
			assertThat(jobs.findByIncidentIdOrderByCreatedAtDesc(incidentId)).hasSize((int)before);
		}
	}

	@Test
	void approvalByAnotherUserAndManualReplyAnalysisNotifyAnalysisRequester() throws Exception {
		Session sender=cmp(), approver=manager(), recipient=login("etch.lee");
		String incidentId=newIncident(sender,false).incidentId();
		String job=json(postAs(sender,"/api/incidents/{id}/questions",
			"{\"question\":\"앞 공정 영향 확인\",\"expectedIncidentVersion\":"+incidentVersion(sender,incidentId)+"}",incidentId)
			.andExpect(status().isAccepted()),"$.data.jobId");
		awaitJob(sender,job,"WAITING_APPROVAL");
		String approvalId=waitingRefId(sender,job);
		var approval=getAs(approver,"/api/approvals/{id}",approvalId);
		String resumed=json(postAs(approver,"/api/approvals/{id}/decisions",
			"{\"decision\":\"APPROVE\",\"expectedVersion\":"+number(approval,"$.data.version")+"}",approvalId)
			.andExpect(status().isOk()),"$.data.resumeJobId");
		getAs(sender,"/api/jobs/{id}",resumed).andExpect(jsonPath("$.data.requestedBy").value("USR-CMP-01"));
		awaitJob(sender,resumed,"WAITING_REPLY");
		String requestId=waitingRefId(sender,resumed);
		acceptRequest(recipient,requestId);
		long before=jobs.findByIncidentIdOrderByCreatedAtDesc(incidentId).size();
		String reply=json(postAs(recipient,"/api/check-requests/{id}/replies",
			"{\"findings\":\"장비 기록 확인 완료\",\"confirmedByUser\":true,\"expectedVersion\":2}",requestId)
			.andExpect(status().isCreated()),"$.data.replyId");
		assertThat(jobs.findByIncidentIdOrderByCreatedAtDesc(incidentId)).hasSize((int)before);
		String followup=json(postAs(sender,"/api/incidents/{id}/followup-analysis-jobs",
			"{\"sourceRefs\":[{\"type\":\"REPLY\",\"id\":\""+reply+"\"}],\"expectedIncidentVersion\":"+incidentVersion(sender,incidentId)+"}",incidentId)
			.andExpect(status().isAccepted()),"$.data.jobId");
		awaitJob(sender,followup,"SUCCEEDED");
		awaitNotification(sender,followup,"ANALYSIS_SUCCEEDED");
		assertThat(notificationsFor(approver,followup)).isEmpty();
		assertThat(notificationsFor(recipient,followup)).isEmpty();
	}

	@Test
	void approvalRejectionDoesNotPretendAnAnalysisResultIsReady() throws Exception {
		Session sender=cmp();
		String incidentId=newIncident(sender,false).incidentId();
		String job=json(postAs(sender,"/api/incidents/{id}/questions",
			"{\"question\":\"앞 공정 확인\",\"expectedIncidentVersion\":"+incidentVersion(sender,incidentId)+"}",incidentId),"$.data.jobId");
		awaitJob(sender,job,"WAITING_APPROVAL");
		String approvalId=waitingRefId(sender,job);
		postAs(sender,"/api/approvals/{id}/decisions","{\"decision\":\"REJECT\",\"comment\":\"기존 확인과 중복\",\"expectedVersion\":1}",approvalId)
			.andExpect(status().isOk());
		assertThat(notificationsFor(sender,job)).isEmpty();
	}

	private void awaitNotification(Session s, String targetId, String type) throws Exception {
		for (int i = 0; i < 100; i++) {
			if (notificationsFor(s, targetId).stream().anyMatch(n -> type.equals(n.get("type")))) {
				return;
			}
			Thread.sleep(25);
		}
		assertThat(notificationsFor(s, targetId)).extracting(n -> n.get("type")).contains(type);
	}
}
