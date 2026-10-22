package com.fabcontext.api.domain.report;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;

import org.junit.jupiter.api.Test;
import com.fabcontext.api.contract.ReportSections;
import com.fabcontext.api.contract.ReportSection;

class ReportTest {

	static final Instant T = Instant.parse("2026-10-20T00:00:00Z");

	static ReportSections sections(String body) {
		return new ReportSections(body, "조사", "요청", "조치", "미해결");
	}

	static Report draft() {
		return Report.builder().reportId("rpt_1").incidentId("inc_1").version(1).title("보고서").sourceRevision(5)
			.sections(sections("b")).createdAt(T).build();
	}

	@Test
	void editIncrementsRevisionAndKeepsTitleWhenBlank() {
		Report r = draft();
		r.checkRevision(1L);
		java.util.List<ReportSection> edited = new java.util.ArrayList<>(sections("new").toList());
		edited.set(0, new ReportSection("overview", "개요(수정한 제목)", "new"));
		r.edit(" ", edited, "u1", T.plusSeconds(1));
		// 배열 [{key, title, body}] — 다섯 키 순서 고정, 제목은 사용자가 바꾼 대로 남는다
		assertThat(r.getSections()).extracting(m -> m.get("key")).containsExactlyElementsOf(ReportSections.TITLES.keySet());
		assertThat(r.getSections().getFirst()).containsEntry("body", "new").containsEntry("title", "개요(수정한 제목)");
		assertThat(r.sectionMap()).containsEntry("overview", "new").containsOnlyKeys(ReportSections.TITLES.keySet());
		assertThat(r.getContentRevision()).isEqualTo(2);
		assertThat(r.getTitle()).isEqualTo("보고서");
		assertThat(r.getUpdatedBy()).isEqualTo("u1");
		assertThatThrownBy(() -> r.checkRevision(1L)).hasFieldOrPropertyWithValue("code", "REVISION_CONFLICT");
		assertThatThrownBy(() -> r.checkRevision(null)).hasFieldOrPropertyWithValue("code", "EXPECTED_REVISION_REQUIRED");
	}

	@Test
	void confirmedReportIsImmutable() {
		Report r = draft();
		r.confirm("u1", T);
		r.attachSnapshot("# md");
		assertThat(r.getStatus()).isEqualTo(ReportStatus.CONFIRMED);
		assertThat(r.getSnapshotRef()).isEqualTo("snapshot://reports/rpt_1/v1/r1");
		assertThat(r.getSnapshotMarkdown()).isEqualTo("# md");
		assertThatThrownBy(() -> r.edit("x", sections("x").toList(), "u1", T)).hasFieldOrPropertyWithValue("code", "REPORT_CONFIRMED");
		assertThatThrownBy(() -> r.confirm("u1", T)).hasFieldOrPropertyWithValue("code", "REPORT_CONFIRMED");
	}

	@Test
	void editRejectsMissingOrReorderedSections() {
		Report r = draft();
		java.util.List<ReportSection> all = sections("b").toList();
		assertThatThrownBy(() -> r.edit("x", all.subList(0, 4), "u1", T)).hasFieldOrPropertyWithValue("code", "INVALID_SECTIONS");
		java.util.List<ReportSection> swapped = new java.util.ArrayList<>(all);
		java.util.Collections.swap(swapped, 0, 1);
		assertThatThrownBy(() -> r.edit("x", swapped, "u1", T)).hasFieldOrPropertyWithValue("code", "INVALID_SECTIONS");
		java.util.List<ReportSection> unknown = new java.util.ArrayList<>(all);
		unknown.set(4, new ReportSection("extra", "추가", "x"));
		assertThatThrownBy(() -> r.edit("x", unknown, "u1", T)).hasFieldOrPropertyWithValue("code", "INVALID_SECTIONS");
	}
}
