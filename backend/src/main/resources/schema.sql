-- Hibernate가 표를 만든 뒤(spring.jpa.defer-datasource-initialization) 시드보다 먼저 돈다. 몇 번을 돌려도 같다.
-- 엔티티 관계로 표현하지 않는 외래 키를 여기서 건다 — 엔티티가 다른 도메인 패키지의 엔티티를 품지 않게 하려고.
-- 표 이름은 연결의 기본 스키마(currentSchema · hikari.schema)를 따른다.
-- 스크립트 전체가 한 문장이다(spring.sql.init.separator) — DO 블록 안의 세미콜론에서 자르지 않는다.
DO $$
BEGIN
	-- 기존 OPEN/REPLIED/CANCELLED 체크 제약을 새 요청 수락·거절 상태까지 확장한다. 데이터는 보존한다.
	ALTER TABLE check_request DROP CONSTRAINT IF EXISTS check_request_status_check;
	ALTER TABLE check_request ADD CONSTRAINT check_request_status_check
		CHECK (status IN ('OPEN','ACCEPTED','REJECTED','REPLIED','CANCELLED'));

	-- 확정본 요약 작업(SUMMARY) · 업무 미수행 종결(CLOSED_UNPERFORMED)을 기존 체크 제약에 더한다. 데이터는 보존한다.
	ALTER TABLE job DROP CONSTRAINT IF EXISTS job_kind_check;
	ALTER TABLE job ADD CONSTRAINT job_kind_check
		CHECK (kind IN ('BASIC','DEEP','RESUME','REPORT','SUMMARY'));
	ALTER TABLE work_item DROP CONSTRAINT IF EXISTS work_item_status_check;
	ALTER TABLE work_item ADD CONSTRAINT work_item_status_check
		CHECK (status IN ('PROPOSED','DECLINED','READY','WAITING_MANAGER','APPROVED','REJECTED','REQUESTED','COMPLETED',
			'CLOSED_UNPERFORMED'));
	-- 확정본 요약 완료 · 실패 알림 종류
	IF to_regclass('user_notification') IS NOT NULL THEN
		ALTER TABLE user_notification DROP CONSTRAINT IF EXISTS user_notification_type_check;
		ALTER TABLE user_notification ADD CONSTRAINT user_notification_type_check
			CHECK (type IN ('INCIDENT_CREATED','CHECK_REQUEST_RECEIVED','CHECK_REQUEST_ACCEPTED','CHECK_REQUEST_REJECTED',
				'CHECK_REQUEST_REPLIED','WORK_APPROVAL_REQUESTED','WORK_APPROVED','WORK_REJECTED','ANALYSIS_SUCCEEDED',
				'ANALYSIS_FAILED','REPORT_SUMMARY_READY','REPORT_SUMMARY_FAILED'));
	END IF;

	-- 사건당 확정본은 하나 — 확정은 사건 행 잠금으로 직렬화하고, 이 인덱스는 다른 경로 · 버그에 대한 마지막 보장이다.
	-- 예전 규칙(확정 뒤 새 판본 재확정)으로 확정본이 둘 이상인 사건이 있으면 만들지 않고 알린다.
	IF to_regclass('uq_report_confirmed_per_incident') IS NULL THEN
		IF EXISTS (SELECT 1 FROM report WHERE status = 'CONFIRMED' GROUP BY incident_id HAVING count(*) > 1) THEN
			RAISE NOTICE 'uq_report_confirmed_per_incident 생략: 확정본이 둘 이상인 사건이 있습니다';
		ELSE
			CREATE UNIQUE INDEX uq_report_confirmed_per_incident ON report (incident_id) WHERE status = 'CONFIRMED';
		END IF;
	END IF;

	-- check_request.approval_id → approval (확인 요청을 만든 승인)
	IF NOT EXISTS (SELECT 1 FROM pg_constraint
			WHERE conname = 'fk_check_request_approval' AND conrelid = to_regclass('check_request')) THEN
		ALTER TABLE check_request ADD CONSTRAINT fk_check_request_approval
			FOREIGN KEY (approval_id) REFERENCES approval (approval_id);
	END IF;

	-- 보고서 목록 · 제목 검색(GET /api/reports) 인덱스 — ops/tools/report_search_explain.sh로 효과를 확인한 것만 둔다.
	-- 제목 중간 일치(ILIKE '%…%')는 B-tree를 못 쓰므로 trigram GIN. 확장은 public에 두고 연산자 클래스를 public.으로
	-- 지정해 어느 스키마(backend · backend_slow)의 표에서도 같은 문장이 통하게 한다.
	CREATE EXTENSION IF NOT EXISTS pg_trgm WITH SCHEMA public;
	CREATE INDEX IF NOT EXISTS idx_report_title_trgm ON report USING gin (title public.gin_trgm_ops);
	-- 검색어 없는 목록의 최신순 첫 페이지 — 사건을 이상 감지일 순서로 읽다가 한 페이지에서 멈춘다
	CREATE INDEX IF NOT EXISTS idx_incident_process_detected ON incident (process_code, detected_at DESC, incident_id);
END $$
