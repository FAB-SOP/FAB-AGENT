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
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 공정 직후 막 두께 계측 — 웨이퍼 한 장의 5위치(CENTER · NORTH · EAST · SOUTH · WEST) 값 (data/site-metrology.json).
 * stage는 AFTER_CVD · AFTER_ETCH · AFTER_CMP, featureId는 film_thickness, specId는 적용 관리한계 ID(QL-…-V1)다.
 */
@Entity
@Table(name = "metrology", indexes = {@Index(columnList = "waferId"), @Index(columnList = "runId")})
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor
@Builder
public class Metrology {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	private String measurementId;

	private String phase;

	private String stage;

	private String lotId;

	private String waferId;

	private String runId;

	/** 외래 키 제약 전용 읽기 연관 — 값은 runId로만 쓰고 읽는다(API 응답 모양을 바꾸지 않으려고 String을 남겼다) */
	@ManyToOne(fetch = FetchType.LAZY)
	@JoinColumn(name = "runId", referencedColumnName = "runId", insertable = false, updatable = false,
		foreignKey = @ForeignKey(name = "fk_metrology_run"))
	@Getter(AccessLevel.NONE)
	private RouteRun runRef;

	private String layerId;

	private String featureId;

	private String siteId;

	private String specId;

	/** 위치 두께(Å). 컬럼 이름은 단위를 붙였다 — 옛 이름 cd_value(PHOTO 공정 CD 계측 시절)는 쓰지 않는다. API 필드 이름은 그대로 */
	@jakarta.persistence.Column(name = "value_a")
	private double value;

	private String unit;

	/** 계측 방법 — 합성 광학 막 모델 */
	private String toolId;

	private Instant measuredAt;

	private Instant availableAt;
}
