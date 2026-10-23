package com.fabcontext.api.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Locale;

import com.fabcontext.api.support.ApiTestSupport;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;

/** backend 스키마 — 외래 키 · jsonb 컬럼 · 컬럼 이름이 실제 Postgres(테스트 컨테이너)에 만들어지는지 */
@DisplayName("DB 스키마 (외래 키 · JSON · 컬럼 이름)")
class SchemaTest extends ApiTestSupport {

	@Autowired
	JdbcTemplate jdbc;

	@Test
	void foreignKeysExist() {
		List<String> fks = jdbc.queryForList("""
			select constraint_name from information_schema.table_constraints
			where constraint_type = 'FOREIGN KEY' and constraint_schema = current_schema()""",
			String.class).stream().map(s -> s.toLowerCase(Locale.ROOT)).toList();
		assertThat(fks).contains("fk_job_incident", "fk_analysis_result_incident", "fk_analysis_result_job",
			"fk_approval_incident", "fk_approval_job", "fk_check_request_incident", "fk_check_request_approval",
			"fk_check_reply_incident", "fk_check_reply_request", "fk_incident_action_incident", "fk_report_incident",
			"fk_metrology_run", "fk_run_feature_run", "fk_route_run_upstream");
	}

	@Test
	void foreignKeyRejectsUnknownRun() {
		assertThatThrownBy(() -> jdbc.update("""
			insert into metrology (run_id, wafer_id, site_id, value_a) values ('RUN-NOPE', 'W-NOPE', 'S1', 1.0)"""))
			.isInstanceOf(DataIntegrityViolationException.class);
	}

	@Test
	void jsonColumnsAndRenamedColumn() {
		assertThat(jdbc.queryForObject("select current_schema()", String.class)).isEqualTo(SCHEMA);
		// @JdbcTypeCode(SqlTypes.JSON) 컬럼 32개가 전부 jsonb다(json · text가 아니다)
		List<String> json = jdbc.queryForList("""
			select table_name || '.' || column_name from information_schema.columns
			where table_schema = current_schema() and data_type in ('json', 'jsonb')""", String.class);
		assertThat(json).hasSize(32).contains("incident.wafer_ids", "incident.run_ids", "user_account.process_scopes", "analysis_request.inputs",
			"priority_evaluation.run_ids", "priority_evaluation.details",
			"incident_action.related_source", "work_item.evidence_ids", "work_item.brief", "job.metrics",
			"report.cause_equipment_parameters", "approval.decision_input");
		assertThat(jdbc.queryForObject("""
			select count(*) from information_schema.columns where table_schema = current_schema() and data_type = 'json'""",
			Integer.class)).isZero();
		// jsonb 연산자가 실제로 먹는다 — 저장소 조회(findEngineersByProcess 등)가 기대는 것
		assertThat(jdbc.queryForObject("""
			select count(*) from incident where wafer_ids @> '["W-CURRENT-009-3"]'::jsonb""", Integer.class)).isPositive();
		assertThat(jdbc.queryForObject("""
			select count(*) from information_schema.columns where table_schema = current_schema() and table_name = 'metrology' and column_name = 'value_a'""",
			Integer.class)).isEqualTo(1);
		assertThat(jdbc.queryForObject("""
			select count(*) from information_schema.columns where table_schema = current_schema() and table_name = 'metrology' and column_name = 'cd_value'""",
			Integer.class)).isZero();
	}
}
