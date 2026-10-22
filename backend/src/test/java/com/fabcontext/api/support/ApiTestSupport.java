package com.fabcontext.api.support;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

import com.jayway.jsonpath.JsonPath;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * API 통합 테스트 공통 지원. 모든 테스트 클래스가 같은 Spring 컨텍스트(시드 데이터 포함)를 공유하므로,
 * 상태를 바꾸는 테스트는 {@link #newIncident}로 자기 사건을 만들어 쓴다.
 * <p>
 * DB는 compose와 같은 이미지의 Postgres 컨테이너 하나를 테스트 실행 전체가 함께 쓴다(실행이 끝나면 Ryuk가 지운다).
 * 컨텍스트는 스키마로 나눈다 — 기본은 {@value #SCHEMA}, 설정이 다른 컨텍스트(SlowAgentApiTest)는 자기 스키마를 쓴다.
 * 테이블은 컨텍스트를 띄울 때마다 새로 만들고 시드한다(ddl-auto=create).
 */
@SpringBootTest(properties = {"fab.agent.step-delay-ms=30", "spring.jpa.hibernate.ddl-auto=create",
		// 묶음 경로는 코드 기본값이 없다 — 테스트 설정이 넣는다(backend 폴더 기준)
		"fab.pack-dir=../data/fab-causal-pack-v2", "fab.rag-dir=../data/fab-rag-real-documents-v1",
		"spring.jpa.properties.hibernate.default_schema=" + ApiTestSupport.SCHEMA,
		"spring.datasource.hikari.schema=" + ApiTestSupport.SCHEMA})
@AutoConfigureMockMvc
public abstract class ApiTestSupport {

	public static final String SCHEMA = "backend";

	/** compose의 postgres와 같은 이미지. 클래스를 처음 쓸 때 한 번 띄우고 모든 컨텍스트가 재사용한다 */
	@ServiceConnection
	static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer(
			DockerImageName.parse("pgvector/pgvector:pg16").asCompatibleSubstituteFor("postgres"));

	static {
		POSTGRES.start();
	}

	public static final String SERVICE_TOKEN = "dev-simulator-token";

	@Autowired
	protected MockMvc mvc;

	protected record Session(MockHttpSession http, String csrf, String username) {
	}

	protected record NewIncident(String incidentId, String jobId) {
	}

	protected Session login(String username) throws Exception {
		MockHttpSession http = new MockHttpSession();
		String csrf = json(mvc.perform(get("/api/auth/csrf").session(http)), "$.data.token");
		mvc.perform(post("/api/auth/login").session(http).header("X-CSRF-TOKEN", csrf)
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"username\":\"" + username + "\",\"password\":\"fab1234!\"}"))
			.andExpect(status().isOk());
		return new Session(http, csrf, username);
	}

	/** 주 시연 사건(EV-1024, CMP) 담당 */
	protected Session cmp() throws Exception {
		return login("cmp.park");
	}

	/** 앞 공정(CVD) 담당 — 확인 요청을 받는 쪽 */
	protected Session cvd() throws Exception {
		return login("cvd.kim");
	}

	protected Session manager() throws Exception {
		return login("manager.choi");
	}

	protected ResultActions getAs(Session s, String url, Object... vars) throws Exception {
		return mvc.perform(get(url, vars).session(s.http()));
	}

	/** CSRF + 새 Idempotency-Key로 POST */
	protected ResultActions postAs(Session s, String url, String body, Object... vars) throws Exception {
		return postWithKey(s, UUID.randomUUID().toString(), url, body, vars);
	}

	protected ResultActions postWithKey(Session s, String key, String url, String body, Object... vars)
			throws Exception {
		return mvc.perform(write(post(url, vars), s, body).header("Idempotency-Key", key));
	}

	protected ResultActions patchAs(Session s, String url, String body, Object... vars) throws Exception {
		return mvc.perform(write(patch(url, vars), s, body));
	}

	private MockHttpServletRequestBuilder write(MockHttpServletRequestBuilder b, Session s, String body) {
		return b.session(s.http()).header("X-CSRF-TOKEN", s.csrf()).contentType(MediaType.APPLICATION_JSON)
			.content(body);
	}

	/** 시뮬레이터 이벤트로 새 사건을 만들고 기본 분석 완료까지 기다린다. */
	protected NewIncident newIncident(Session watcher, boolean failFirstAnalysis, String... wafers) throws Exception {
		// 팩의 L009 CMP-PM3 웨이퍼 — 실제 경로상 앞 공정(ETCH-PM2) 제거량이 정상 변동 밖이다
		List<String> waferIds = wafers.length == 0 ? List.of("W-CURRENT-009-3") : Arrays.asList(wafers);
		String event = """
			{"eventId":"EVT-TEST-%s","anomalyType":"THICKNESS_OOC_R1","severity":"MEDIUM","processCode":"CMP",
			 "equipmentId":"CMP-PM3","lotId":"CURRENT-L009","waferIds":%s,"runIds":%s,
			 "sensorIds":["head_pressure"],"detectedAt":"%s","metrics":{"simulateAgentFailure":%s}}
			""".formatted(UUID.randomUUID(), toJson(waferIds),
				toJson(waferIds.stream().map(w -> "RUN-" + w + "-CMP").toList()), "2026-10-19T01:30:00Z", failFirstAnalysis);
		ResultActions result = ingest(event).andExpect(status().isAccepted());
		NewIncident created = new NewIncident(json(result, "$.data.incidentId"), json(result, "$.data.jobId"));
		awaitJob(watcher, created.jobId(), failFirstAnalysis ? "FAILED" : "SUCCEEDED");
		return created;
	}

	protected ResultActions ingest(String eventJson) throws Exception {
		return mvc.perform(post("/api/events/anomalies").header("X-Service-Token", SERVICE_TOKEN)
			.contentType(MediaType.APPLICATION_JSON).content(eventJson));
	}

	/** 작업이 기대 상태 중 하나에 도달할 때까지 기다리고 그 상태를 돌려준다. */
	protected String awaitJob(Session s, String jobId, String... expected) throws Exception {
		List<String> wanted = Arrays.asList(expected);
		String last = null;
		for (int i = 0; i < 200; i++) {
			last = json(getAs(s, "/api/jobs/{id}", jobId), "$.data.status");
			if (wanted.contains(last)) {
				return last;
			}
			Thread.sleep(25);
		}
		assertThat(last).as("job %s status", jobId).isIn(wanted);
		return last;
	}

	protected void acceptRequest(Session recipient, String requestId) throws Exception {
		postAs(recipient,"/api/check-requests/{id}/decisions","{\"decision\":\"ACCEPT\",\"expectedVersion\":1}",requestId).andExpect(status().isOk());
	}

	protected String waitingRefId(Session s, String jobId) throws Exception {
		return json(getAs(s, "/api/jobs/{id}", jobId), "$.data.waitingRef.id");
	}

	protected long incidentVersion(Session s, String incidentId) throws Exception {
		return number(getAs(s, "/api/incidents/{id}", incidentId), "$.data.version");
	}

	protected String seededIncident(Session s, String displayId) throws Exception {
		return json(getAs(s, "/api/incidents?q=" + displayId), "$.data.items[0].incidentId");
	}

	protected static <T> T json(ResultActions result, String path) throws Exception {
		return JsonPath.read(result.andReturn().getResponse().getContentAsString(), path);
	}

	protected static long number(ResultActions result, String path) throws Exception {
		Number n = json(result, path);
		return n.longValue();
	}

	protected static String toJson(List<String> values) {
		return values.stream().map(v -> "\"" + v + "\"").reduce((a, b) -> a + "," + b).map(v -> "[" + v + "]")
			.orElse("[]");
	}
}
