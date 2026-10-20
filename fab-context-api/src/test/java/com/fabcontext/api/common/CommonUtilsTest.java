package com.fabcontext.api.common;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

class CommonUtilsTest {

	@Test
	void cursorPagesThroughListAndRejectsGarbage() {
		List<Integer> all = IntStream.range(0, 5).boxed().toList();
		PageResponse<Integer> p1 = Cursors.page(all, null, 2, i -> i);
		assertThat(p1.items()).containsExactly(0, 1);
		PageResponse<Integer> p2 = Cursors.page(all, p1.nextCursor(), 2, i -> i);
		PageResponse<Integer> p3 = Cursors.page(all, p2.nextCursor(), 2, i -> i);
		assertThat(p2.items()).containsExactly(2, 3);
		assertThat(p3.items()).containsExactly(4);
		assertThat(p3.nextCursor()).isNull();
		assertThat(Cursors.page(all, Cursors.encode(99), 2, i -> i).items()).isEmpty();
		assertThat(Cursors.page(all, null, null, i -> i).items()).hasSize(5);
		assertThatThrownBy(() -> Cursors.page(all, "abc", 2, i -> i)).isInstanceOf(ApiException.class)
			.hasFieldOrPropertyWithValue("code", "INVALID_CURSOR");
		assertThatThrownBy(() -> Cursors.page(all, Cursors.encode(-1), 2, i -> i)).isInstanceOf(ApiException.class);
		assertThatThrownBy(() -> Cursors.page(all, null, 0, i -> i)).hasFieldOrPropertyWithValue("code", "INVALID_PAGE_SIZE");
	}

	@Test
	void canonicalHashIgnoresKeyOrder() {
		Map<String, Object> a = new LinkedHashMap<>();
		a.put("b", 1);
		a.put("a", Map.of("y", 2, "x", 1));
		Map<String, Object> b = new LinkedHashMap<>();
		b.put("a", Map.of("x", 1, "y", 2));
		b.put("b", 1);
		assertThat(Hashing.canonicalSha256(a)).isEqualTo(Hashing.canonicalSha256(b)).startsWith("sha256:").hasSize(71);
		assertThat(Hashing.canonicalSha256(Map.of("b", 2))).isNotEqualTo(Hashing.canonicalSha256(Map.of("b", 1)));
	}

	@Test
	void idsArePrefixedAndUnique() {
		assertThat(Ids.next("inc")).matches("inc_[0-9a-z]{12}");
		assertThat(IntStream.range(0, 1000).mapToObj(i -> Ids.next("x")).distinct().count()).isEqualTo(1000);
	}

	@Test
	void jsonFieldsReadNullAsEmpty() {
		// JSON 컬럼(jsonb)은 null을 그대로 돌려준다 — Builder로 만드는 엔티티의 getter가 빈 값으로 바꾼다
		var result = com.fabcontext.api.domain.analysis.AnalysisResult.builder().observedFacts(null).limitations(null).build();
		assertThat(result.getObservedFacts()).isEmpty();
		assertThat(result.getLimitations()).isEmpty();
		assertThat(com.fabcontext.api.domain.checkrequest.Reply.builder().evidenceIds(null).build().getEvidenceIds()).isEmpty();
		assertThat(Json.read(Json.write(List.of("a", "한글")), new tools.jackson.core.type.TypeReference<List<String>>() {
		})).containsExactly("a", "한글");
		assertThat(Json.write(Map.of("s", "v"))).contains("\"s\"");
	}

	@Test
	void versionCheckProduces409WithCurrentVersion() {
		ApiException.checkVersion("x", 3L, 3);
		assertThatThrownBy(() -> ApiException.checkVersion("x", 2L, 3))
			.isInstanceOfSatisfying(ApiException.class, e -> {
				assertThat(e.getStatus()).isEqualTo(HttpStatus.CONFLICT);
				assertThat(e.getDetails()).containsEntry("currentVersion", 3L);
			});
		assertThatThrownBy(() -> ApiException.checkVersion("x", null, 3))
			.hasFieldOrPropertyWithValue("status", HttpStatus.UNPROCESSABLE_ENTITY);
	}
}
