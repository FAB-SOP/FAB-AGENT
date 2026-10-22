-- Hibernate가 표를 만든 뒤(spring.jpa.defer-datasource-initialization) 시드보다 먼저 돈다. 몇 번을 돌려도 같다.
-- 엔티티 관계로 표현하지 않는 외래 키를 여기서 건다 — 엔티티가 다른 도메인 패키지의 엔티티를 품지 않게 하려고.
-- 표 이름은 연결의 기본 스키마(currentSchema · hikari.schema)를 따른다.
-- 스크립트 전체가 한 문장이다(spring.sql.init.separator) — DO 블록 안의 세미콜론에서 자르지 않는다.
DO $$
BEGIN
	-- check_request.approval_id → approval (확인 요청을 만든 승인)
	IF NOT EXISTS (SELECT 1 FROM pg_constraint
			WHERE conname = 'fk_check_request_approval' AND conrelid = to_regclass('check_request')) THEN
		ALTER TABLE check_request ADD CONSTRAINT fk_check_request_approval
			FOREIGN KEY (approval_id) REFERENCES approval (approval_id);
	END IF;
END $$
