package com.fabcontext.api.auth;

import com.fabcontext.api.config.FabProperties;
import jakarta.servlet.DispatcherType;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.ProviderManager;
import org.springframework.security.authentication.dao.DaoAuthenticationProvider;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.security.web.csrf.CsrfTokenRepository;
import org.springframework.security.web.csrf.CsrfTokenRequestAttributeHandler;
import org.springframework.security.web.csrf.HttpSessionCsrfTokenRepository;

@Configuration
@EnableWebSecurity
public class SecurityConfig {

	public static final String CSRF_HEADER = "X-CSRF-TOKEN";

	@Bean
	public SecurityFilterChain securityFilterChain(HttpSecurity http, FabProperties properties,
			CsrfTokenRepository csrfTokenRepository, SecurityContextRepository securityContextRepository)
			throws Exception {
		http
			.csrf(csrf -> csrf
				.csrfTokenRepository(csrfTokenRepository)
				// /api/auth/csrf 응답 token을 그대로 X-CSRF-TOKEN 헤더에 넣어 쓰는 SPA 방식
				.csrfTokenRequestHandler(new CsrfTokenRequestAttributeHandler())
				.ignoringRequestMatchers("/api/events/**", "/internal/**", "/h2-console/**"))
			.securityContext(sc -> sc.securityContextRepository(securityContextRepository))
			.headers(h -> h.frameOptions(f -> f.sameOrigin()))
			.formLogin(f -> f.disable())
			.httpBasic(b -> b.disable())
			.logout(l -> l.disable())
			.addFilterBefore(new ServiceTokenFilter(properties.serviceToken(), properties.internalToken()),
				UsernamePasswordAuthenticationFilter.class)
			.authorizeHttpRequests(auth -> auth
				.dispatcherTypeMatchers(DispatcherType.ASYNC, DispatcherType.ERROR).permitAll()
				.requestMatchers(HttpMethod.GET, "/api/auth/csrf").permitAll()
				.requestMatchers(HttpMethod.POST, "/api/auth/login").permitAll()
				.requestMatchers("/api/events/**").hasRole("SERVICE")
				.requestMatchers("/internal/**").hasRole("AGENT")
				.requestMatchers("/swagger-ui.html", "/swagger-ui/**", "/v3/api-docs/**", "/h2-console/**")
				.permitAll()
				.requestMatchers("/api/**").hasAnyRole("ENGINEER", "MANAGER")
				.anyRequest().permitAll())
			.exceptionHandling(e -> e
				.authenticationEntryPoint(JsonSecurityHandlers.entryPoint())
				.accessDeniedHandler(JsonSecurityHandlers.accessDenied()));
		return http.build();
	}

	@Bean
	public CsrfTokenRepository csrfTokenRepository() {
		HttpSessionCsrfTokenRepository repository = new HttpSessionCsrfTokenRepository();
		repository.setHeaderName(CSRF_HEADER);
		return repository;
	}

	@Bean
	public SecurityContextRepository securityContextRepository() {
		return new HttpSessionSecurityContextRepository();
	}

	@Bean
	public PasswordEncoder passwordEncoder() {
		return new BCryptPasswordEncoder();
	}

	@Bean
	public AuthenticationManager authenticationManager(AuthUserDetailsService userDetailsService,
			PasswordEncoder passwordEncoder) {
		DaoAuthenticationProvider provider = new DaoAuthenticationProvider(userDetailsService);
		provider.setPasswordEncoder(passwordEncoder);
		return new ProviderManager(provider);
	}
}
