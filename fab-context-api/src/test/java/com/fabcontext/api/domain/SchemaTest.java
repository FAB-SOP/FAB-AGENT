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

/** backend 스키마 — 외래 키 · JSON 컬럼 · 컬럼 이름이 H2(테스트)에도 만들어지는지. Postgres는 README 절차로 확인한다 */
@DisplayName("DB 스키마 (외래 키 · JSON · 컬럼 이름)")
class SchemaTest extends ApiTestSupport {

	@Autowired
	JdbcTemplate jdbc;

	@Test
	void foreignKeysExist() {
		List<String> fks = jdbc.queryForList("""
			select constraint_name from information_schema.table_constraints where constraint_type = 'FOREIGN KEY'""",
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
		String type = jdbc.queryForObject("""
			select data_type from information_schema.columns where lower(table_name) = 'incident' and lower(column_name) = 'wafer_ids'""",
			String.class);
		assertThat(type).isEqualToIgnoringCase("JSON");
		assertThat(jdbc.queryForObject("""
			select count(*) from information_schema.columns where lower(table_name) = 'metrology' and lower(column_name) = 'value_a'""",
			Integer.class)).isEqualTo(1);
		assertThat(jdbc.queryForObject("""
			select count(*) from information_schema.columns where lower(table_name) = 'metrology' and lower(column_name) = 'cd_value'""",
			Integer.class)).isZero();
	}
}
