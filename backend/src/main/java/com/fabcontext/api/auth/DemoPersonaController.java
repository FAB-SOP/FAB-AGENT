package com.fabcontext.api.auth;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import com.fabcontext.api.common.ApiException;
import com.fabcontext.api.common.ApiResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.web.bind.annotation.*;

/** 로그인 이후 시연 계정 사이에서만 전환한다. 운영 기본값은 비활성화. */
@RestController
@RequestMapping("/api/auth/demo-personas")
public class DemoPersonaController {
 private static final List<String> ACCOUNTS=List.of("cvd.kim","etch.lee","cmp.park","mnt.jung","manager.choi");
 private final UserAccountRepository users;
 private final SecurityContextRepository contexts;
 private final boolean enabled;
 public DemoPersonaController(UserAccountRepository users,SecurityContextRepository contexts,
   @Value("${fab.demo-persona-switch:false}") boolean enabled) {this.users=users;this.contexts=contexts;this.enabled=enabled;}
 public record Selection(@NotBlank String username) {}
 @GetMapping
 public ApiResponse<?> list(@AuthenticationPrincipal AuthUser current) {
  requireLogin(current);
  return ApiResponse.of(Map.of("enabled",enabled,"items",enabled?ACCOUNTS.stream()
   .flatMap(name->users.findByUsername(name).stream()).map(u->AuthDtos.SessionUser.from(AuthUser.from(u).withoutPassword())).toList():List.of()));
 }
 @PostMapping
 public ApiResponse<AuthDtos.Session> select(@AuthenticationPrincipal AuthUser current,
   @RequestHeader(SecurityConfig.CSRF_HEADER) String csrf,@Valid @RequestBody Selection input,
   HttpServletRequest request,HttpServletResponse response) {
  requireLogin(current);
  if(!enabled) throw ApiException.notFound("demoPersonaSwitch","disabled");
  if(!ACCOUNTS.contains(current.username())||!ACCOUNTS.contains(input.username())) throw ApiException.forbidden("DEMO_ACCOUNT_REQUIRED","등록된 시연 계정 사이에서만 전환할 수 있습니다.");
  var principal=users.findByUsername(input.username()).map(AuthUser::from).map(AuthUser::withoutPassword)
   .orElseThrow(()->ApiException.notFound("demoAccount",input.username()));
  request.changeSessionId();
  var context=SecurityContextHolder.createEmptyContext();
  context.setAuthentication(UsernamePasswordAuthenticationToken.authenticated(principal,null,principal.getAuthorities()));
  SecurityContextHolder.setContext(context);contexts.saveContext(context,request,response);
  var session=request.getSession(false);
  return ApiResponse.of(AuthDtos.Session.of(principal,Instant.ofEpochMilli(session.getLastAccessedTime()).plusSeconds(session.getMaxInactiveInterval())));
 }
 private static void requireLogin(AuthUser user) {if(user==null)throw ApiException.unauthorized("먼저 로그인하세요.");}
}
