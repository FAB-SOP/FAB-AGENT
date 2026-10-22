package com.fabcontext.api.domain.processdata;

import java.time.Instant;

import jakarta.persistence.FetchType;
import jakarta.persistence.ForeignKey;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 공정 run 하나 (fab-causal-pack-v1 data/process-runs.json). 웨이퍼 한 장이 CVD → ETCH → CMP를 한 번씩 거친다.
 * 입고 · 출고 평균 두께와 그 차이(증착량 · 제거량), 계측 공개 시각, R1(관리한계) · 규격 판정을 함께 가진다.
 */
@Entity
@Table(name = "route_run", uniqueConstraints = @UniqueConstraint(name = "uk_route_run_run_id", columnNames = "runId"),
		indexes = {@Index(columnList = "waferId"),
		@Index(columnList = "phase,processCode,equipmentId")})
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor
@Builder
public class RouteRun {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	private String runId;

	private String waferId;

	private String lotId;

	/** BASELINE(기준 운전) · CURRENT(현재 로트) · FOLLOWUP(후속 확인 run) */
	private String phase;

	/** CVD=1 · ETCH=2 · CMP=3 */
	private int sequenceNo;

	private String processCode;

	private String stepName;

	/** 공정 모듈. 예: CMP-PM3 — 이름이 같아도 앞뒤 장비를 연결하지 않고 wafer 경로를 따른다 */
	private String equipmentId;

	private String chamberId;

	private String recipeId;

	private String recipeVersion;

	private String productId;

	private String layerId;

	private String material;

	private Instant startedAt;

	private Instant endedAt;

	private int readySeconds;

	private int processSeconds;

	private int shutdownSeconds;

	private String controlMode;

	/** 입고 평균 두께(Å) — CVD는 0 */
	private double inputMeanA;

	/** 출고 평균 두께(Å) = 5위치 평균 */
	private double outputMeanA;

	/** |출고 − 입고| — CVD는 증착량, ETCH · CMP는 제거량 */
	private double addedOrRemovedA;

	private int changeSign;

	private String metrologyId;

	private String upstreamRunId;

	/** 외래 키 제약 전용 읽기 연관 — 값은 upstreamRunId로만 쓰고 읽는다(API 응답 모양을 바꾸지 않으려고 String을 남겼다) */
	@ManyToOne(fetch = FetchType.LAZY)
	@JoinColumn(name = "upstreamRunId", referencedColumnName = "runId", insertable = false, updatable = false,
		foreignKey = @ForeignKey(name = "fk_route_run_upstream"))
	@Getter(AccessLevel.NONE)
	private RouteRun upstreamRunRef;

	/** 계측 결과를 쓸 수 있게 된 시각 — 이 시각 전의 분석은 이 run의 계측을 쓰지 않는다 */
	private Instant metrologyAvailableAt;

	/** 웨이퍼 평균이 동결 관리한계(I 관리도) 밖 — R1만 등록한다 */
	private boolean oocR1;

	/** 제품 규격 밖 (CMP만 가상 규격이 있다) */
	private boolean oos;
}
