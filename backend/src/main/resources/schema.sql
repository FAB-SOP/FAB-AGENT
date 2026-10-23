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
