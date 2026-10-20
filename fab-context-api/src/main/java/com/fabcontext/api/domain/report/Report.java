package com.fabcontext.api.domain.report;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.fabcontext.api.common.ApiException;
import com.fabcontext.api.domain.incident.Incident;
import jakarta.persistence.FetchType;
import jakarta.persistence.ForeignKey;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Lob;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import com.fabcontext.api.contract.ReportSections;
import com.fabcontext.api.contract.ReportSection;

/**
 * 사건 보고서 버전. 확정(CONFIRMED) 후에는 수정할 수 없고, 변경이 필요하면 새 version 초안을 만든다.
 * contentRevision은 편집 충돌(409) 검증용이다.
 */
@Entity
@Table(name = "report", indexes = @Index(columnList = "incidentId"))
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Report {

	@Id
	private String reportId;

	private String incidentId;

	/** 외래 키 제약 전용 읽기 연관 — 값은 incidentId로만 쓰고 읽는다(API 응답 모양을 바꾸지 않으려고 String을 남겼다) */
	@ManyToOne(fetch = FetchType.LAZY)
	@JoinColumn(name = "incidentId", insertable = false, updatable = false,
		foreignKey = @ForeignKey(name = "fk_report_incident"))
	@Getter(AccessLevel.NONE)
	private Incident incidentRef;

	private String processCode;

	/** 사건 내 보고서 버전 번호 (1, 2, ...) */
	private int version;

	@Enumerated(EnumType.STRING)
	private ReportStatus status;

	private String title;

	private long contentRevision;

	/** 초안 생성 시점의 사건 버전 */
	private long sourceRevision;

	/** 본문 [{key, title, body}] — 다섯 키(overview · investigation · requestsAndReplies · actionsAndVerification · unresolved) 순서 고정 */
	@JdbcTypeCode(SqlTypes.JSON)
	private List<Map<String, Object>> sections = new ArrayList<>();

	@JdbcTypeCode(SqlTypes.JSON)
	private List<String> evidenceIds = new ArrayList<>();

	private Integer baseReportVersion;

	private String generatedByJobId;

	private String snapshotRef;

	@Lob
	private String snapshotMarkdown;

	private String createdBy;

	private Instant createdAt;

	private String updatedBy;

	private Instant updatedAt;

	private String confirmedBy;

	private Instant confirmedAt;

	@jakarta.persistence.Version
	private long rowVersion;

	@Builder
	public Report(String reportId, String incidentId, String processCode, int version, String title,
			long sourceRevision, ReportSections sections, List<String> evidenceIds,
			Integer baseReportVersion, String generatedByJobId, String createdBy, Instant createdAt) {
		this.reportId = reportId;
		this.incidentId = incidentId;
		this.processCode = processCode;
		this.version = version;
		this.status = ReportStatus.DRAFT;
		this.title = title;
		this.contentRevision = 1;
		this.sourceRevision = sourceRevision;
		this.sections = rows(sections.toList());
		this.evidenceIds = evidenceIds == null ? new ArrayList<>() : new ArrayList<>(evidenceIds);
		this.baseReportVersion = baseReportVersion;
		this.generatedByJobId = generatedByJobId;
		this.createdBy = createdBy;
		this.createdAt = createdAt;
		this.updatedAt = createdAt;
	}

	void edit(String newTitle, List<ReportSection> newSections, String userId, Instant at) {
		requireDraft();
		if (newTitle != null && !newTitle.isBlank()) {
			title = newTitle;
		}
		sections = rows(ReportSections.validate(newSections));
		contentRevision++;
		updatedBy = userId;
		updatedAt = at;
	}

	void confirm(String userId, Instant at) {
		requireDraft();
		status = ReportStatus.CONFIRMED;
		snapshotRef = "snapshot://reports/" + reportId + "/v" + version + "/r" + contentRevision;
		confirmedBy = userId;
		confirmedAt = at;
		updatedAt = at;
	}

	void attachSnapshot(String markdown) {
		snapshotMarkdown = markdown;
	}

	void checkRevision(Long expected) {
		if (expected == null) {
			throw ApiException.unprocessable("EXPECTED_REVISION_REQUIRED", "expectedContentRevision이 필요합니다.");
		}
		if (expected != contentRevision) {
			throw ApiException.conflict("REVISION_CONFLICT", "다른 사용자가 먼저 편집했습니다. 최신 contentRevision을 조회해 병합하세요.",
				Map.of("expectedContentRevision", expected, "currentContentRevision", contentRevision));
		}
	}

	void requireDraft() {
		if (status != ReportStatus.DRAFT) {
			throw ApiException.conflict("REPORT_CONFIRMED", "확정된 보고서는 수정할 수 없습니다. 새 초안을 요청하세요.",
				Map.of("reportId", reportId, "version", version));
		}
	}

	private static List<Map<String, Object>> rows(List<ReportSection> list) {
		List<Map<String, Object>> out = new ArrayList<>();
		for (ReportSection s : list) {
			Map<String, Object> m = new LinkedHashMap<>();
			m.put("key", s.key());
			m.put("title", s.title());
			m.put("body", s.body());
			out.add(m);
		}
		return out;
	}

	/** 다섯 키 객체 — 내부 API(팀 계약 Report.sections)가 쓴다 */
	public Map<String, Object> sectionMap() {
		return ReportSections.toMap(sections);
	}
}
