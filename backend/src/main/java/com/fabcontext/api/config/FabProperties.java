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
		// 묶음 경로는 기본값을 두지 않는다 — 원본은 compose 환경 변수(FAB_PACK_DIR · FAB_RAG_DIR). 직접 띄울 때도 넘긴다
		if (packDir == null || packDir.isBlank()) {
			throw new IllegalStateException("FAB_PACK_DIR(데이터 팩 경로)가 없습니다 — compose는 넣어 준다. 직접 띄울 때: "
					+ "FAB_PACK_DIR=../data/fab-causal-pack-v2 (backend 폴더 기준)");
		}
		if (ragDir == null || ragDir.isBlank()) {
			throw new IllegalStateException("FAB_RAG_DIR(실제 원문 RAG 경로)가 없습니다 — compose는 넣어 준다. 직접 띄울 때: "
					+ "FAB_RAG_DIR=../data/fab-rag-real-documents-v1");
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
