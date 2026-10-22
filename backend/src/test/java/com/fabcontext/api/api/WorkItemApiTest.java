package com.fabcontext.api.api;
import java.util.UUID;
import com.fabcontext.api.support.ApiTestSupport;
import static org.hamcrest.Matchers.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import org.junit.jupiter.api.Test;

class WorkItemApiTest extends ApiTestSupport {
	@org.springframework.beans.factory.annotation.Autowired
	private com.fabcontext.api.domain.job.JobRepository jobs;
	@org.springframework.beans.factory.annotation.Autowired
	private com.fabcontext.api.auth.UserAccountRepository accounts;
	private String propose(Session s,String id,String operation,String title) throws Exception {
		String result=json(getAs(s,"/api/incidents/{id}/analysis-results",id),"$.data.resultId");
		return json(postAs(s,"/api/incidents/{id}/work-items","{\"operationCode\":\""+operation+"\",\"title\":\""+title+"\",\"reason\":\"분석 결과에서 필요한 후속 확인\",\"sourceResultId\":\""+result+"\",\"expectedIncidentVersion\":"+incidentVersion(s,id)+"}",id).andExpect(status().isCreated()),"$.data.taskId");
	}
	private String completion(Session s,String id,long version) throws Exception {return "{\"description\":\"실측과 기록을 확인한 결과를 남김\",\"performedAt\":\"2026-01-01T00:00:00Z\",\"confirmedByUser\":true,\"expectedVersion\":"+version+",\"expectedIncidentVersion\":"+incidentVersion(s,id)+"}";}
	@Test
	void localCheckNeedsAdoptionButNoManagerAndCreatesActualRecordOnlyOnCompletion() throws Exception {
		Session s=cmp();String id=newIncident(s,false).incidentId();String task=propose(s,id,"REVIEW_SENSOR_DATA","유량 실측값과 설정값 비교 확인");
		getAs(s,"/api/incidents/{id}/actions",id).andExpect(jsonPath("$.data.items",hasSize(0)));
		String key=UUID.randomUUID().toString();
		var adopted=postWithKey(s,key,"/api/work-items/{id}/adoptions","{\"expectedVersion\":1}",task).andExpect(status().isOk()).andExpect(jsonPath("$.data.status").value("READY")).andExpect(jsonPath("$.data.approverUserId").doesNotExist());
		long version=((Number)json(adopted,"$.data.version")).longValue();
		postWithKey(s,key,"/api/work-items/{id}/adoptions","{\"expectedVersion\":1}",task).andExpect(header().string("Idempotent-Replayed","true"));
		getAs(s,"/api/incidents/{id}/actions",id).andExpect(jsonPath("$.data.items",hasSize(0)));
		postAs(s,"/api/work-items/{id}/completions",completion(s,id,version),task).andExpect(status().isOk()).andExpect(jsonPath("$.data.status").value("COMPLETED"));
		getAs(s,"/api/incidents/{id}/actions",id).andExpect(jsonPath("$.data.items",hasSize(1))).andExpect(jsonPath("$.data.items[0].relatedSource.refId").value(task));
		org.assertj.core.api.Assertions.assertThat(jobs.findByIncidentIdOrderByCreatedAtDesc(id)).hasSize(1);
		getAs(s,"/api/incidents/{id}/investigation-results",id).andExpect(jsonPath("$.data.items[0].sourceRef.type").value("ACTION"));
	}
	@Test
	void changePlanNeedsAssignedManagerAndApprovalDoesNotRecordExecution() throws Exception {
		Session s=cmp();String id=newIncident(s,false).incidentId();String task=propose(s,id,"CHANGE_SETPOINT","압력 설정값 변경 계획 검토");
		var adopted=postAs(s,"/api/work-items/{id}/adoptions","{\"expectedVersion\":1}",task).andExpect(status().isOk()).andExpect(jsonPath("$.data.status").value("WAITING_MANAGER")).andExpect(jsonPath("$.data.approverUserId").value("USR-MGR-01"));
		long version=((Number)json(adopted,"$.data.version")).longValue();
		postAs(s,"/api/work-items/{id}/decisions","{\"decision\":\"APPROVE\",\"expectedVersion\":"+version+"}",task).andExpect(status().isForbidden());
		postAs(s,"/api/work-items/{id}/completions",completion(s,id,version),task).andExpect(status().isConflict());
		postAs(s,"/api/incidents/{id}/actions","{\"actionType\":\"INSPECTION\",\"description\":\"우회 기록\",\"performedAt\":\"2026-01-01T00:00:00Z\",\"confirmedByUser\":true,\"expectedIncidentVersion\":"+incidentVersion(s,id)+",\"relatedSource\":{\"type\":\"WORK_ITEM\",\"refId\":\""+task+"\"}}",id).andExpect(status().isUnprocessableEntity());
		var approved=postAs(manager(),"/api/work-items/{id}/decisions","{\"decision\":\"APPROVE\",\"expectedVersion\":"+version+"}",task).andExpect(status().isOk()).andExpect(jsonPath("$.data.status").value("APPROVED"));
		getAs(s,"/api/incidents/{id}/actions",id).andExpect(jsonPath("$.data.items",hasSize(0)));
		postAs(s,"/api/work-items/{id}/completions",completion(s,id,((Number)json(approved,"$.data.version")).longValue()),task).andExpect(status().isOk());
		getAs(s,"/api/incidents/{id}/actions",id).andExpect(jsonPath("$.data.items[0].relatedSource.managerApproved").value(true));
	}
	@Test
	void declinedAndRejectedPlansDoNotBecomeExecutableAndChangeWordingCannotBeDowngradedToCheck() throws Exception {
		Session s=cmp();String id=newIncident(s,false).incidentId();String task=propose(s,id,"REVIEW_SENSOR_DATA","확인 후 압력 설정값 조정");
		getAs(s,"/api/incidents/{id}/work-items",id).andExpect(jsonPath("$.data.items[0].route").value("MANAGER_APPROVAL"));
		postAs(s,"/api/work-items/{id}/decisions","{\"decision\":\"DECLINE\",\"comment\":\"우선 확인만 진행\",\"expectedVersion\":1}",task).andExpect(status().isOk()).andExpect(jsonPath("$.data.status").value("DECLINED"));
		postAs(s,"/api/work-items/{id}/adoptions","{\"expectedVersion\":2}",task).andExpect(status().isConflict());
		getAs(s,"/api/incidents/{id}/actions",id).andExpect(jsonPath("$.data.items",hasSize(0)));
	}
	@Test
	void declineWithoutReasonPreservesProposalInIncidentHistory() throws Exception {
		Session s=cmp();
		String id=newIncident(s,false).incidentId();
		String task=propose(s,id,"REVIEW_SENSOR_DATA","압력 기록 확인");
		postAs(s,"/api/work-items/{id}/decisions","{\"decision\":\"DECLINE\",\"expectedVersion\":1}",task)
			.andExpect(status().isOk()).andExpect(jsonPath("$.data.status").value("DECLINED"));
		getAs(s,"/api/incidents/{id}/work-items",id)
			.andExpect(jsonPath("$.data.items",hasSize(1)))
			.andExpect(jsonPath("$.data.items[0].taskId").value(task))
			.andExpect(jsonPath("$.data.items[0].status").value("DECLINED"));
		getAs(s,"/api/work-items?box=mine").andExpect(jsonPath("$.data.items[*].taskId",not(hasItem(task))));
		postAs(s,"/api/work-items/{id}/adoptions","{\"expectedVersion\":2}",task).andExpect(status().isConflict());
	}
	@Test
	void managerRejectionRequiresReasonAndDoesNotAllowCompletion() throws Exception {
		Session s=cmp();String id=newIncident(s,false).incidentId();String task=propose(s,id,"CHANGE_RECIPE","레시피 변경 계획 검토");
		var adopted=postAs(s,"/api/work-items/{id}/adoptions","{\"expectedVersion\":1}",task);
		long version=((Number)json(adopted,"$.data.version")).longValue();
		Session manager=manager();
		postAs(manager,"/api/work-items/{id}/decisions","{\"decision\":\"REJECT\",\"expectedVersion\":"+version+"}",task).andExpect(status().isUnprocessableEntity());
		var rejected=postAs(manager,"/api/work-items/{id}/decisions","{\"decision\":\"REJECT\",\"comment\":\"추가 근거 필요\",\"expectedVersion\":"+version+"}",task).andExpect(status().isOk()).andExpect(jsonPath("$.data.status").value("REJECTED"));
		postAs(s,"/api/work-items/{id}/completions",completion(s,id,((Number)json(rejected,"$.data.version")).longValue()),task).andExpect(status().isConflict());
		getAs(s,"/api/incidents/{id}/actions",id).andExpect(jsonPath("$.data.items",hasSize(0)));
	}

	@Test
	void teamRequestUsesRegisteredRecipientAndRealUpstreamEquipment() throws Exception {
		Session s=cmp();String id=newIncident(s,false).incidentId();String task=propose(s,id,"UPSTREAM_CHECK","앞 공정 계측 결과 확인");
		postAs(s,"/api/work-items/{id}/adoptions","{\"expectedVersion\":1,\"ownerUserId\":\"USR-MNT-01\",\"targetProcessCode\":\"ETCH\"}",task).andExpect(status().isUnprocessableEntity());
		var adopted=postAs(s,"/api/work-items/{id}/adoptions","{\"expectedVersion\":1,\"ownerUserId\":\"USR-ETCH-01\",\"targetProcessCode\":\"ETCH\"}",task).andExpect(status().isOk()).andExpect(jsonPath("$.data.status").value("REQUESTED"));
		String request=json(adopted,"$.data.requestId");Session etch=login("etch.lee");acceptRequest(etch,request);
		getAs(etch,"/api/check-requests/{id}",request).andExpect(jsonPath("$.data.equipmentId").value("ETCH-PM2"));
		postAs(etch,"/api/check-requests/{id}/replies","{\"findings\":\"처리 이력 확인 완료\",\"confirmedByUser\":true,\"expectedVersion\":2}",request).andExpect(status().isCreated()).andExpect(jsonPath("$.data.resumeJobId").doesNotExist());
		getAs(s,"/api/incidents/{id}/work-items",id).andExpect(jsonPath("$.data.items[0].status").value("COMPLETED"));
		getAs(s,"/api/incidents/{id}/check-requests",id).andExpect(jsonPath("$.data.items[0].status").value("REPLIED")).andExpect(jsonPath("$.data.items[0].replies[0].findings").isNotEmpty());
		getAs(manager(),"/api/incidents/{id}/check-requests",id).andExpect(status().isOk());
		getAs(cvd(),"/api/incidents/{id}/check-requests",id).andExpect(status().isForbidden());
		var reportJob=postAs(s,"/api/incidents/{id}/report-jobs","{\"expectedIncidentVersion\":"+incidentVersion(s,id)+"}",id).andExpect(status().isAccepted());
		String reportJobId=json(reportJob,"$.data.jobId");awaitJob(s,reportJobId,"SUCCEEDED");
		String reportId=json(getAs(s,"/api/jobs/{id}",reportJobId),"$.data.resultRef.id");
		getAs(s,"/api/reports/{id}",reportId).andExpect(jsonPath("$.data.sections[2].body").value(containsString("요청자: 박연마"))).andExpect(jsonPath("$.data.sections[2].body").value(containsString("발송 검토자:"))).andExpect(jsonPath("$.data.sections[2].body").value(containsString("확인·회신자:")));

	}
	@Test
	void maintenanceCanReplyButCannotAdoptAndOtherProcessCannotSeeDrafts() throws Exception {
		Session s=cmp();String id=newIncident(s,false).incidentId();String task=propose(s,id,"EQUIPMENT_INSPECTION","공급 펌프·배관 현장 점검");
		postAs(login("mnt.jung"),"/api/work-items/{id}/adoptions","{\"expectedVersion\":1}",task).andExpect(status().isForbidden());
		getAs(cvd(),"/api/incidents/{id}/work-items",id).andExpect(status().isForbidden());
		var adopted=postAs(s,"/api/work-items/{id}/adoptions","{\"expectedVersion\":1,\"ownerUserId\":\"USR-MNT-01\",\"targetProcessCode\":\"CMP\"}",task).andExpect(status().isOk());
		String request=json(adopted,"$.data.requestId");
		acceptRequest(login("mnt.jung"),request);
		postAs(login("mnt.jung"),"/api/check-requests/{id}/replies","{\"findings\":\"펌프 현장 확인 결과 기록\",\"confirmedByUser\":true,\"expectedVersion\":2}",request).andExpect(status().isCreated()).andExpect(jsonPath("$.data.resumeJobId").doesNotExist());
		getAs(s,"/api/incidents/{id}/work-items",id).andExpect(jsonPath("$.data.items[0].status").value("COMPLETED"));
	}
	@Test
	void processScopedManagerIsPreferredAndOnlyAssignedManagerMayApprove() throws Exception {
		String uid="USR-MID-"+UUID.randomUUID();String username="mid."+UUID.randomUUID();
		var parent=accounts.findByUsername("manager.choi").orElseThrow();
		accounts.save(new com.fabcontext.api.auth.UserAccount(uid,username,parent.getPasswordHash(),"CMP 승인 담당",com.fabcontext.api.auth.Role.MANAGER,java.util.List.of("CMP"),"연마기술팀"));
		try {
			Session s=cmp();String id=newIncident(s,false).incidentId();String task=propose(s,id,"CHANGE_RECIPE","레시피 변경 계획 검토");
			var adopted=postAs(s,"/api/work-items/{id}/adoptions","{\"expectedVersion\":1}",task).andExpect(status().isOk()).andExpect(jsonPath("$.data.approverUserId").value(uid));
			long version=((Number)json(adopted,"$.data.version")).longValue();
			postAs(manager(),"/api/work-items/{id}/decisions","{\"decision\":\"APPROVE\",\"expectedVersion\":"+version+"}",task).andExpect(status().isForbidden());
			postAs(login(username),"/api/work-items/{id}/decisions","{\"decision\":\"APPROVE\",\"expectedVersion\":"+version+"}",task).andExpect(status().isOk());
		} finally {accounts.deleteById(uid);}
	}

	@Test
	void repeatedSuggestionAcrossAnalysesKeepsOneTaskButDifferentSensorRemainsSeparate() throws Exception {
		Session s=cmp();String id=newIncident(s,false).incidentId();
		String first=propose(s,id,"REVIEW_SENSOR_DATA","슬러리 유량 실측값과 설정값 비교 확인");
		String second=propose(s,id,"REVIEW_SENSOR_DATA","슬러리 유량: 실측값과 설정값 비교 확인");
		org.assertj.core.api.Assertions.assertThat(second).isEqualTo(first);
		var job=postAs(s,"/api/incidents/{id}/basic-analysis-jobs","{\"expectedIncidentVersion\":"+incidentVersion(s,id)+"}",id).andExpect(status().isAccepted());
		awaitJob(s,json(job,"$.data.jobId"),"SUCCEEDED");
		org.assertj.core.api.Assertions.assertThat(propose(s,id,"REVIEW_SENSOR_DATA","슬러리 유량 실측값과 설정값 비교 확인")).isEqualTo(first);
		propose(s,id,"REVIEW_SENSOR_DATA","헤드 압력 실측값과 설정값 비교 확인");
		getAs(s,"/api/incidents/{id}/work-items",id).andExpect(jsonPath("$.data.items",hasSize(2)));
	}

}
