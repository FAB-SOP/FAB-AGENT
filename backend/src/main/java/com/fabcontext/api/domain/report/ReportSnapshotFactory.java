package com.fabcontext.api.domain.report;

import java.util.LinkedHashMap;
import java.util.Map;

import com.fabcontext.api.domain.incident.Incident;
import org.springframework.stereotype.Component;

/**
 * 확정본(jsonb) — 확정 시점의 제목 · 다섯 칸 · 분류 · 근거 · 판본 · 확정자를 한 번 고정한다.
 * 필드는 팀 계약(내부 확정본 조회)과 같고, 시각은 ISO 문자열로 둔다.
 */
@Component
class ReportSnapshotFactory {

	Map<String, Object> create(Report report, Incident incident) {
		Map<String, Object> snapshot = new LinkedHashMap<>();
		snapshot.put("reportId", report.getReportId());
		snapshot.put("reportVersion", report.getVersion());
		snapshot.put("contentRevision", report.getContentRevision());
		snapshot.put("snapshotRef", report.getSnapshotRef());
		snapshot.put("title", report.getTitle());
		snapshot.put("incidentDisplayId", incident.getDisplayId());
		snapshot.put("sections", report.sectionMap());
		snapshot.put("classification", classification(report.getClassification()));
		snapshot.put("evidenceIds", report.getEvidenceIds());
		snapshot.put("confirmedBy", report.getConfirmedBy());
		snapshot.put("confirmedAt", report.getConfirmedAt().toString());
		return snapshot;
	}

	private static Map<String, Object> classification(ReportCauseClassification classification) {
		if (classification == null) {
			return null;
		}
		Map<String, Object> view = new LinkedHashMap<>();
		view.put("category", classification.getCauseCategory().name());
		view.put("equipmentParameters", classification.getCauseEquipmentParameters());
		return view;
	}
}
