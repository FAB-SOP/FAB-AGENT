package com.fabcontext.api.domain.report;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import jakarta.persistence.Embeddable;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 확정된 보고서 판본의 원인 분류. 확정과 함께 한 번 만들어지고 바뀌지 않는다.
 * 분류 기능 도입 전에 확정된 보고서는 이 값이 없다(null).
 */
@Embeddable
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ReportCauseClassification {

	@Enumerated(EnumType.STRING)
	private ReportCauseCategory causeCategory;

	/** MACHINE일 때만 값이 있다 — 사건 공정의 센서 ID, 센서 카탈로그 순서 */
	@JdbcTypeCode(SqlTypes.JSON)
	private List<String> causeEquipmentParameters = new ArrayList<>();

	/** 직접 확정이면 확정한 사람, 승인 경로면 승인한 사람 */
	private String classifiedBy;

	private Instant classifiedAt;

	private ReportCauseClassification(ReportCauseCategory causeCategory, List<String> causeEquipmentParameters,
			String classifiedBy, Instant classifiedAt) {
		this.causeCategory = causeCategory;
		this.causeEquipmentParameters = new ArrayList<>(causeEquipmentParameters);
		this.classifiedBy = classifiedBy;
		this.classifiedAt = classifiedAt;
	}

	/** 검사를 마친 값으로만 만든다 — {@link CauseClassificationPolicy#validate} */
	static ReportCauseClassification of(ReportCauseCategory category, List<String> equipmentParameters,
			String classifiedBy, Instant classifiedAt) {
		return new ReportCauseClassification(category, equipmentParameters, classifiedBy, classifiedAt);
	}

	public List<String> getCauseEquipmentParameters() {
		return causeEquipmentParameters == null ? List.of() : List.copyOf(causeEquipmentParameters);
	}
}
