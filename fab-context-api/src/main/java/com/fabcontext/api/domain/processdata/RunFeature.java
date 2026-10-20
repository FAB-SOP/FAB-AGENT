package com.fabcontext.api.domain.processdata;

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
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * run 하나 · 센서 하나의 본 처리 안정 구간 특징 (data/run-sensor-features.json).
 * PROCESS 시작 후 2초 전이를 뺀 구간의 평균 · 표준편차 · 최소 · 최대 · 기울기와 설정값.
 * BASELINE run은 원시 시계열 없이 이 특징만 있다.
 */
@Entity
@Table(name = "run_feature", indexes = {@Index(columnList = "runId"), @Index(columnList = "phase,processCode,sensorId")})
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor
@Builder
public class RunFeature {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	private String runId;

	/** 외래 키 제약 전용 읽기 연관 — 값은 runId로만 쓰고 읽는다(API 응답 모양을 바꾸지 않으려고 String을 남겼다) */
	@ManyToOne(fetch = FetchType.LAZY)
	@JoinColumn(name = "runId", referencedColumnName = "runId", insertable = false, updatable = false,
		foreignKey = @ForeignKey(name = "fk_run_feature_run"))
	@Getter(AccessLevel.NONE)
	private RouteRun runRef;

	private String waferId;

	private String lotId;

	private String phase;

	private String processCode;

	private String equipmentId;

	private String sensorId;

	private String unit;

	private String recipeStep;

	private double mean;

	private double std;

	private double min;

	private double max;

	private double slopePerSecond;

	/** 설정값 — 반사 RF · 모터 전류처럼 설정이 없는 관측 태그는 null */
	private Double setpoint;
}
