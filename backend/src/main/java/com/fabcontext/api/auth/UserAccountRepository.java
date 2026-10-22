package com.fabcontext.api.auth;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface UserAccountRepository extends JpaRepository<UserAccount, String> {

	Optional<UserAccount> findByUsername(String username);

	/** 그 공정을 맡은 ENGINEER(설비정비팀 포함) — process_scopes(jsonb) 포함 연산자로 DB에서 거른다 */
	@Query(value = """
		select * from {h-schema}user_account
		where role = 'ENGINEER' and process_scopes @> jsonb_build_array(cast(:processCode as text))
		order by user_id""", nativeQuery = true)
	List<UserAccount> findEngineersByProcess(String processCode);
}
