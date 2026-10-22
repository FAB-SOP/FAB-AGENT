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
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("P12 지식 · 자료 검색")
class KnowledgeApiTest extends ApiTestSupport {

	@Test
	void searchRanksRelevantSectionsAndRespectsScope() throws Exception {
		Session etch = cmp();
		// 지식 코퍼스: 실제 원문 7편 · 팀 점검 문서 15 · 가상 사례 7 (동료 납품 묶음)
		getAs(etch, "/api/knowledge/documents?q=헤드 압력")
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.items[*].docId", hasItem("DEMO-CMP_PRESSURE")))
			.andExpect(jsonPath("$.data.items[0].score").value(both(greaterThan(0.0)).and(lessThanOrEqualTo(1.0))))
			.andExpect(jsonPath("$.data.items[*].processCode", everyItem(oneOf("CMP", "COMMON"))))
			.andExpect(jsonPath("$.data.note").value(containsString("원인 확률이 아닙니다")));
		getAs(cvd(), "/api/knowledge/documents?q=가스 공급")
			.andExpect(jsonPath("$.data.items[*].docId", hasItem("DEMO-CVD_GAS")))
			.andExpect(jsonPath("$.data.items[*].processCode", everyItem(oneOf("CVD", "COMMON"))));
		getAs(etch, "/api/knowledge/documents?q=전구체").andExpect(jsonPath("$.data.items[*].docId", not(hasItem("DEMO-CVD_GAS"))));
	}

	/** 실제 원문 조각은 공개 저장소에 없다(출판자 저작물) — 팀 내부로 받아 둔 경우에만 원문 문서를 확인한다 */
	static void requireSourceChunks() {
		Assumptions.assumeTrue(java.nio.file.Files.exists(java.nio.file.Path.of(
			"../data/fab-rag-real-documents-v1/rag/source-chunks.jsonl")), "실제 원문 조각 없음");
	}

	@Test
	void filtersAndPaging() throws Exception {
		requireSourceChunks();
		Session etch = cmp();
		getAs(etch, "/api/knowledge/documents?docType=SYNTHETIC_CASE")
			.andExpect(jsonPath("$.data.items[*].docType", everyItem(is("SYNTHETIC_CASE"))))
			.andExpect(jsonPath("$.data.items[*].isApprovedProcedure", everyItem(is(false))));
		getAs(etch, "/api/knowledge/documents?docType=SOURCE_DOCUMENT")
			.andExpect(jsonPath("$.data.items[*].docId", hasItem("DOC01")));
		getAs(etch, "/api/knowledge/documents?processCode=COMMON").andExpect(status().isOk())
			.andExpect(jsonPath("$.data.items[*].processCode", everyItem(is("COMMON"))));
		getAs(etch, "/api/knowledge/documents?processCode=CVD").andExpect(status().isForbidden());
		var first = getAs(etch, "/api/knowledge/documents?size=1").andExpect(jsonPath("$.data.items", hasSize(1)));
		String cursor = json(first, "$.data.nextCursor");
		getAs(etch, "/api/knowledge/documents?size=1&cursor=" + cursor).andExpect(jsonPath("$.data.items", hasSize(1)));
		getAs(etch, "/api/knowledge/documents?q=존재하지않는단어").andExpect(jsonPath("$.data.items", hasSize(0)));
	}

	@Test
	void documentMetadataVersionsAndContent() throws Exception {
		requireSourceChunks();
		Session etch = cmp();
		// 실제 원문 DOC01(MIT/TI CMP) — 원문 쪽별 조각 10개가 단락이다. 공개 요약 9개는 원문과 겹쳐 넣지 않았다
		getAs(etch, "/api/knowledge/documents/DOC01")
			.andExpect(jsonPath("$.data.version").value("2026-10-20"))
			.andExpect(jsonPath("$.data.latest").value(true))
			.andExpect(jsonPath("$.data.versions", contains("2026-10-20")))
			.andExpect(jsonPath("$.data.synthetic").value(false))
			.andExpect(jsonPath("$.data.sourceUrl").value(startsWith("https://")))
			.andExpect(jsonPath("$.data.sections", hasSize(10)));
		getAs(etch, "/api/knowledge/documents/PUB-S01").andExpect(status().isNotFound());
		getAs(etch, "/api/knowledge/documents/DOC01?version=v9").andExpect(status().isNotFound());
		getAs(etch, "/api/knowledge/documents/NOPE").andExpect(status().isNotFound());
		getAs(etch, "/api/knowledge/documents/DEMO-CVD_GAS").andExpect(status().isForbidden());

		String text = getAs(etch, "/api/knowledge/documents/DOC01/content").andExpect(status().isOk())
			.andExpect(header().string("Content-Type", startsWith("text/plain")))
			.andReturn().getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
		assertThat(text).startsWith("# [실제 원문]").contains("공개 자료").contains("승인 절차 아님");
		getAs(etch, "/api/knowledge/documents/DEMO-CASE-001/content").andExpect(content().string(containsString("가상")));
		getAs(etch, "/api/knowledge/documents/DOC-COMMON-001/content").andExpect(status().isOk());
	}
}
