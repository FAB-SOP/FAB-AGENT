package com.fabcontext.api.domain.processdata;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

/** 정비 · 설정 · 재료 변경 이력 (data/change-events.json). 실제 변경 시각과 기록(공개) 시각을 분리한다. */
@Entity
@Table(name = "change_record")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor
@Builder
public class ChangeRecord {

	@Id
	private String changeId;

	private String processCode;

	private String equipmentId;

	private String chamberId;

	/** CHAMBER_SERVICE · MAINTENANCE · RECIPE · MATERIAL · CONFIG */
	private String changeType;

	@Column(length = 1000)
	private String summary;

	@Column(name = "before_value", length = 1000)
	private String before;

	@Column(name = "after_value", length = 1000)
	private String after;

	private Instant occurredAt;

	private Instant recordedAt;

	private String recordedBy;

	/** 근본 원인이 확인된 기록인지 — 정비 기록이 있다고 원인이 확정된 것은 아니다 */
	private boolean rootCauseConfirmed;

	@JdbcTypeCode(SqlTypes.JSON)
	@Builder.Default
	private List<String> evidenceIds = new ArrayList<>();

	/** DB 값이 null이면 빈 값 — JSON 컬럼은 null을 그대로 돌려주므로 예전 변환기와 같은 동작을 여기서 지킨다 */
	public List<String> getEvidenceIds() {
		return evidenceIds == null ? List.of() : evidenceIds;
	}
}
