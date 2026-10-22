package com.fabcontext.api.domain.action;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Embeddable
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor
public class ActionVerification {

	private String verificationId;

	@Enumerated(EnumType.STRING)
	private VerificationStatus outcome;

	@Column(length = 2000)
	private String rationale;

	@JdbcTypeCode(SqlTypes.JSON)
	private List<String> evidenceIds = new ArrayList<>();

	private String verifiedBy;

	private Instant verifiedAt;

	/** DB 값이 null이면 빈 값 — JSON 컬럼은 null을 그대로 돌려주므로 예전 변환기와 같은 동작을 여기서 지킨다 */
	public List<String> getEvidenceIds() {
		return evidenceIds == null ? List.of() : evidenceIds;
	}
}
