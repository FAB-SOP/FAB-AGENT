package com.fabcontext.api.config;

import java.io.InputStream;

import io.swagger.v3.core.util.Json31;
import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.PathItem;
import io.swagger.v3.oas.models.media.Schema;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springdoc.core.customizers.OpenApiCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class OpenApiConfig {

	@Bean
	public OpenAPI fabContextOpenApi() {
		return new OpenAPI()
			.info(new Info()
				.title("FAB CONTEXT Public API")
				.version("0.1.0")
				.description("""
					프론트 계약 data/backend-handoff-v2/combined.openapi.json(41개 operation: 기존 38 + 모니터링 3) 구현. 브라우저는 세션 쿠키 + X-CSRF-TOKEN,
					쓰기는 Idempotency-Key · expectedVersion(낙관적 버전) 검증을 사용한다.
					GET /api/auth/csrf → POST /api/auth/login 순서로 로그인한다."""))
			.components(new Components()
				.addSecuritySchemes("sessionCookie", new SecurityScheme()
					.type(SecurityScheme.Type.APIKEY).in(SecurityScheme.In.COOKIE).name("FABSESSION"))
				.addSecuritySchemes("serviceToken", new SecurityScheme()
					.type(SecurityScheme.Type.APIKEY).in(SecurityScheme.In.HEADER).name("X-Service-Token")));
	}

	/**
	 * 모니터링 3개 경로의 문서는 프론트 통합 계약(data/backend-handoff-v2/combined.openapi.json)의 정의를 그대로 붙인다.
	 * 응답은 데이터 팩 JSON을 그대로 내리는 Map이라 springdoc이 스키마를 만들지 못하기 때문이다. 실제 응답이 이 계약 · 예시와 같은지는
	 * MonitoringApiTest가 필드 · 값 단위로 확인한다.
	 */
	@Bean
	public OpenApiCustomizer monitoringContract() {
		return openApi -> {
			try (InputStream in = getClass().getResourceAsStream("/openapi/monitoring-contract.json")) {
				var tree = Json31.mapper().readTree(in);
				var paths = tree.get("paths").properties();
				for (var e : paths) {
					openApi.getPaths().addPathItem(e.getKey(), Json31.mapper().treeToValue(e.getValue(), PathItem.class));
				}
				for (var e : tree.get("components").get("schemas").properties()) {
					openApi.getComponents().addSchemas(e.getKey(), Json31.mapper().treeToValue(e.getValue(), Schema.class));
				}
			}
			catch (Exception ex) {
				throw new IllegalStateException("모니터링 계약 문서를 읽지 못했습니다", ex);
			}
		};
	}
}
