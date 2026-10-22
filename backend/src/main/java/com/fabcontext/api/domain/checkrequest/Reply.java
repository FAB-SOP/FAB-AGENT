package com.fabcontext.api.domain.checkrequest;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import com.fabcontext.api.domain.incident.Incident;
import jakarta.persistence.Column;
import jakarta.persistence.FetchType;
import jakarta.persistence.ForeignKey;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

/** 담당자가 직접 확인한 회신. 담당자 진술은 근거로 보존한다. */
@Entity
@Table(name = "check_reply", indexes = @Index(columnList = "requestId"))
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor
@Builder
public class Reply {

	@Id
	private String replyId;

	private String requestId;

	/** 외래 키 제약 전용 읽기 연관 — 값은 requestId로만 쓰고 읽는다(API 응답 모양을 바꾸지 않으려고 String을 남겼다) */
	@ManyToOne(fetch = FetchType.LAZY)
	@JoinColumn(name = "requestId", insertable = false, updatable = false,
		foreignKey = @ForeignKey(name = "fk_check_reply_request"))
	@Getter(AccessLevel.NONE)
	private CheckRequest requestRef;

	private String incidentId;

	/** 외래 키 제약 전용 읽기 연관 — 값은 incidentId로만 쓰고 읽는다(API 응답 모양을 바꾸지 않으려고 String을 남겼다) */
	@ManyToOne(fetch = FetchType.LAZY)
	@JoinColumn(name = "incidentId", insertable = false, updatable = false,
		foreignKey = @ForeignKey(name = "fk_check_reply_incident"))
	@Getter(AccessLevel.NONE)
	private Incident incidentRef;

	@Column(length = 4000)
	private String findings;

	@JdbcTypeCode(SqlTypes.JSON)
	@Builder.Default
	private List<String> evidenceIds = new ArrayList<>();

	private String authorUserId;

	private boolean confirmedByUser;

	private Instant createdAt;

	@lombok.Setter
	private String resumeJobId;

	/** DB 값이 null이면 빈 값 — JSON 컬럼은 null을 그대로 돌려주므로 예전 변환기와 같은 동작을 여기서 지킨다 */
	public List<String> getEvidenceIds() {
		return evidenceIds == null ? List.of() : evidenceIds;
	}
}
