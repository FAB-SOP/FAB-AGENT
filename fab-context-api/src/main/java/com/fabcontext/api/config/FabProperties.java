package com.fabcontext.api.config;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("fab")
/**
 * @param serviceToken 이벤트 접수(/api/events/**) 서비스 토큰
 * @param internalToken Agent · 업무 MCP가 내부 API(/internal/**)를 부를 때 쓰는 서비스 토큰 (Authorization: Bearer)
 * @param demoRoutes 시연 · 평가 전용 경로(/internal/demo/** — 장애 주입)를 열지
 * @param packDir 데이터 팩(fab-causal-pack-v1) 폴더 — 센서 · 계측 · 이력 · 가상 사례의 유일한 원본
 * @param ragDir 실제 원문 선별 RAG(fab-rag-real-documents-v1) 폴더 — 기술 문서 색인의 원본
 */
public record FabProperties(String serviceToken, String internalToken, Duration approvalTtl, Seed seed, Agent agent,
		boolean demoRoutes, String packDir, String ragDir) {

	public FabProperties {
		if (approvalTtl == null) {
			approvalTtl = Duration.ofHours(24);
		}
		if (seed == null) {
			seed = new Seed(true);
		}
		if (packDir == null || packDir.isBlank()) {
			packDir = "../FAB-실제문서-선별RAG-센서데이터-통합/fab-causal-pack-v1";
		}
		if (ragDir == null || ragDir.isBlank()) {
			ragDir = "../FAB-실제문서-선별RAG-센서데이터-통합/fab-rag-real-documents-v1";
		}
		if (internalToken == null) {
			internalToken = "dev-service-token";
		}
		if (agent == null) {
			agent = new Agent(1500, 0.0, "simulator");
		}
	}

	public record Seed(boolean enabled) {
	}

	/**
	 * @param mode simulator — 서버 안 시뮬레이터가 작업을 처리 / external — fab-agent Worker가 내부 API로 작업을 가져가 처리
	 */
	public record Agent(long stepDelayMs, double failureRate, String mode) {

		public Agent {
			if (mode == null || mode.isBlank()) {
				mode = "simulator";
			}
		}

		public boolean external() {
			return "external".equalsIgnoreCase(mode);
		}
	}
}
