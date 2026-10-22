package com.fabcontext.api.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fabcontext.api.support.ApiTestSupport;
import org.junit.jupiter.api.Test;

class FollowupOpenApiTest extends ApiTestSupport {

	@Test
	void swaggerContainsConcreteFollowupAndDecisionContracts() throws Exception {
		String document=mvc.perform(get("/v3/api-docs")).andExpect(status().isOk())
			.andReturn().getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
		var root=new tools.jackson.databind.json.JsonMapper().readTree(document);
		assertThat(root.at("/paths/~1api~1incidents~1{id}~1followup-analysis-jobs/post/responses/202/content/application~1json/schema/$ref").asText())
			.endsWith("/FollowupAcceptedEnvelope");
		assertThat(root.at("/components/schemas/FollowupAcceptedEnvelope/properties/data/$ref").asText()).endsWith("/FollowupAccepted");
		assertThat(root.at("/components/schemas/CheckRequestDecisionInput/properties/decision/enum").toString()).contains("ACCEPT","REJECT");
		assertThat(root.at("/paths/~1api~1check-requests~1{requestId}~1decisions/post/responses/200/content/application~1json/schema/$ref").asText())
			.endsWith("/DecisionEnvelope");
		assertThat(root.at("/components/schemas/WorkInbox/properties/incomingRequests/type").asText()).isEqualTo("array");
		String export=System.getenv("FAB_OPENAPI_EXPORT");
		if(export!=null) {
			var path=java.nio.file.Path.of(export);
			java.nio.file.Files.createDirectories(path.getParent());
			java.nio.file.Files.writeString(path,document);
		}
	}
}
