package com.fabcontext.api.auth;

import lombok.RequiredArgsConstructor;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class AuthUserDetailsService implements UserDetailsService {

	private final UserAccountRepository repository;

	@Override
	@Transactional(readOnly = true)
	public UserDetails loadUserByUsername(String username) {
		return repository.findByUsername(username)
			.map(AuthUser::from)
			.orElseThrow(() -> new UsernameNotFoundException(username));
	}
}
