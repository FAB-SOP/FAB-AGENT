package com.fabcontext.api.domain.knowledge;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

/** 기술자료 한 버전. sections: [{sectionId, heading, text}] */
@Entity
@Table(name = "knowledge_document", uniqueConstraints = @UniqueConstraint(columnNames = {"docId", "version"}))
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor
@Builder
public class KnowledgeDocument {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	private String docId;

	private String version;

	private boolean latest;

	private String title;

	/** PROCEDURE · REFERENCE · CASE · MANUAL */
	private String docType;

	/** CVD · ETCH · CMP · COMMON */
	private String processCode;

	private String sourceUrl;

	/** 합성(시연용) 자료 여부 */
	private boolean synthetic;

	/** 현장에서 승인된 실행 절차인지. 공개 자료·시연 사례는 false */
	private boolean approvedProcedure;

	@Column(length = 2000)
	private String summary;

	@JdbcTypeCode(SqlTypes.JSON)
	@Builder.Default
	private List<Map<String, Object>> sections = new ArrayList<>();

	private Instant publishedAt;

	public String resourceUri() {
		return "manual://" + docId + "/" + version;
	}

	/** DB 값이 null이면 빈 값 — JSON 컬럼은 null을 그대로 돌려주므로 예전 변환기와 같은 동작을 여기서 지킨다 */
	public List<Map<String, Object>> getSections() {
		return sections == null ? List.of() : sections;
	}
}
