package com.fabcontext.api.domain.analysis;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import com.fabcontext.api.domain.incident.Incident;
import com.fabcontext.api.domain.job.Job;
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

/**
 * Agent 분석 결과. 관측 사실(observedFacts)과 가설(candidateCauses)을 분리하고,
 * 가설마다 지지·반대 근거와 다음 확인 항목을 둔다.
 */
@Entity
@Table(name = "analysis_result", indexes = @Index(columnList = "incidentId"))
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor
@Builder
public class AnalysisResult {

	@Id
	private String resultId;

	private String incidentId;

	/** 외래 키 제약 전용 읽기 연관 — 값은 incidentId로만 쓰고 읽는다(API 응답 모양을 바꾸지 않으려고 String을 남겼다) */
	@ManyToOne(fetch = FetchType.LAZY)
	@JoinColumn(name = "incidentId", insertable = false, updatable = false,
		foreignKey = @ForeignKey(name = "fk_analysis_result_incident"))
	@Getter(AccessLevel.NONE)
	private Incident incidentRef;

	private String jobId;

	/** 외래 키 제약 전용 읽기 연관 — 값은 jobId로만 쓰고 읽는다(API 응답 모양을 바꾸지 않으려고 String을 남겼다) */
	@ManyToOne(fetch = FetchType.LAZY)
	@JoinColumn(name = "jobId", insertable = false, updatable = false,
		foreignKey = @ForeignKey(name = "fk_analysis_result_job"))
	@Getter(AccessLevel.NONE)
	private Job jobRef;

	private String threadId;

	/** BASIC · DEEP · RESUME */
	private String kind;

	@Column(length = 2000)
	private String question;

	@Column(length = 2000)
	private String summary;

	/** [{factId, statement, evidenceIds}] */
	@JdbcTypeCode(SqlTypes.JSON)
	@Builder.Default
	private List<Map<String, Object>> observedFacts = new ArrayList<>();

	/** [{causeId, hypothesis, processCode, priority, status, supportingEvidenceIds, counterEvidenceIds, nextChecks}] */
	@JdbcTypeCode(SqlTypes.JSON)
	@Builder.Default
	private List<Map<String, Object>> candidateCauses = new ArrayList<>();

	@JdbcTypeCode(SqlTypes.JSON)
	@Builder.Default
	private List<String> limitations = new ArrayList<>();

	/** [{evidenceId, type, label, ref}] */
	@JdbcTypeCode(SqlTypes.JSON)
	@Builder.Default
	private List<Map<String, Object>> evidenceRefs = new ArrayList<>();

	/** [{type, label, target}] 화면 이동·제안 후보 */
	@JdbcTypeCode(SqlTypes.JSON)
	@Builder.Default
	private List<Map<String, Object>> nextActions = new ArrayList<>();

	private String dataVersion;

	private Instant createdAt;

	/** DB 값이 null이면 빈 값 — JSON 컬럼은 null을 그대로 돌려주므로 예전 변환기와 같은 동작을 여기서 지킨다 */
	public List<Map<String, Object>> getObservedFacts() {
		return observedFacts == null ? List.of() : observedFacts;
	}

	/** DB 값이 null이면 빈 값 — JSON 컬럼은 null을 그대로 돌려주므로 예전 변환기와 같은 동작을 여기서 지킨다 */
	public List<Map<String, Object>> getCandidateCauses() {
		return candidateCauses == null ? List.of() : candidateCauses;
	}

	/** DB 값이 null이면 빈 값 — JSON 컬럼은 null을 그대로 돌려주므로 예전 변환기와 같은 동작을 여기서 지킨다 */
	public List<String> getLimitations() {
		return limitations == null ? List.of() : limitations;
	}

	/** DB 값이 null이면 빈 값 — JSON 컬럼은 null을 그대로 돌려주므로 예전 변환기와 같은 동작을 여기서 지킨다 */
	public List<Map<String, Object>> getEvidenceRefs() {
		return evidenceRefs == null ? List.of() : evidenceRefs;
	}

	/** DB 값이 null이면 빈 값 — JSON 컬럼은 null을 그대로 돌려주므로 예전 변환기와 같은 동작을 여기서 지킨다 */
	public List<Map<String, Object>> getNextActions() {
		return nextActions == null ? List.of() : nextActions;
	}
}
