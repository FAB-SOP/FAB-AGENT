package com.fabcontext.api.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fabcontext.api.support.ApiTestSupport;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;

@DisplayName("P01 인증 · 세션 · CSRF")
class AuthApiTest extends ApiTestSupport {

	@Test
	void csrfTokenIsIssuedWithoutLogin() throws Exception {
		mvc.perform(get("/api/auth/csrf"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.token").isNotEmpty())
			.andExpect(jsonPath("$.data.headerName").value("X-CSRF-TOKEN"))
			.andExpect(jsonPath("$.meta.requestId").value(startsWith("req_")))
			.andExpect(header().exists("X-Request-Id"));
	}

	@Test
	void loginReturnsSessionUserAndRotatesSessionId() throws Exception {
		MockHttpSession http = new MockHttpSession();
		String before = http.getId();
		String csrf = json(mvc.perform(get("/api/auth/csrf").session(http)), "$.data.token");
		mvc.perform(post("/api/auth/login").session(http).header("X-CSRF-TOKEN", csrf)
				.contentType(MediaType.APPLICATION_JSON).content("{\"username\":\"manager.choi\",\"password\":\"fab1234!\"}"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.user.userId").value("USR-MGR-01"))
			.andExpect(jsonPath("$.data.role").value("MANAGER"))
			.andExpect(jsonPath("$.data.processScopes", containsInAnyOrder("CVD", "ETCH", "CMP")))
			.andExpect(jsonPath("$.data.authenticated").value(true));
		assertThat(http.getId()).as("session fixation protection").isNotEqualTo(before);
	}

	@Test
	void loginRequiresCsrfToken() throws Exception {
		mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
				.content("{\"username\":\"cmp.park\",\"password\":\"fab1234!\"}"))
			.andExpect(status().isForbidden())
			.andExpect(jsonPath("$.error.code").value("CSRF_INVALID"));
	}

	@Test
	void wrongPasswordIs401() throws Exception {
		MockHttpSession http = new MockHttpSession();
		String csrf = json(mvc.perform(get("/api/auth/csrf").session(http)), "$.data.token");
		mvc.perform(post("/api/auth/login").session(http).header("X-CSRF-TOKEN", csrf)
				.contentType(MediaType.APPLICATION_JSON).content("{\"username\":\"cmp.park\",\"password\":\"nope\"}"))
			.andExpect(status().isUnauthorized())
			.andExpect(jsonPath("$.error.code").value("UNAUTHENTICATED"));
	}

	@Test
	void blankCredentialsAre422() throws Exception {
		MockHttpSession http = new MockHttpSession();
		String csrf = json(mvc.perform(get("/api/auth/csrf").session(http)), "$.data.token");
		mvc.perform(post("/api/auth/login").session(http).header("X-CSRF-TOKEN", csrf)
				.contentType(MediaType.APPLICATION_JSON).content("{\"username\":\"\",\"password\":\"\"}"))
			.andExpect(status().isUnprocessableEntity())
			.andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"))
			.andExpect(jsonPath("$.error.details.fields", hasSize(2)));
	}

	@Test
	void protectedApisRequireLogin() throws Exception {
		mvc.perform(get("/api/auth/session")).andExpect(status().isUnauthorized());
		mvc.perform(get("/api/incidents")).andExpect(status().isUnauthorized())
			.andExpect(jsonPath("$.error.code").value("UNAUTHENTICATED"));
		mvc.perform(get("/api/dashboard")).andExpect(status().isUnauthorized());
	}

	@Test
	void logoutInvalidatesSession() throws Exception {
		Session s = cmp();
		getAs(s, "/api/auth/session").andExpect(status().isOk()).andExpect(jsonPath("$.data.user.username").value("cmp.park"));
		mvc.perform(post("/api/auth/logout").session(s.http()).header("X-CSRF-TOKEN", s.csrf()))
			.andExpect(status().isNoContent());
		assertThat(s.http().isInvalid()).isTrue();
	}

	@Test
	void logoutWithoutCsrfIsRejected() throws Exception {
		Session s = cmp();
		mvc.perform(post("/api/auth/logout").session(s.http())).andExpect(status().isForbidden());
	}
}
