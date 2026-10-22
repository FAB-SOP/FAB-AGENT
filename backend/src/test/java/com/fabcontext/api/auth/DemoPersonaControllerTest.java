package com.fabcontext.api.auth;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.util.List;
import java.util.Optional;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.security.core.context.SecurityContextHolder;
class DemoPersonaControllerTest {
 @Test void enabledSwitchRotatesSessionAndUsesActualSelectedAccountWithoutPassword() {
  var users=mock(UserAccountRepository.class);var contexts=mock(SecurityContextRepository.class);
  var account=new UserAccount("USR-ETCH-01","etch.lee","hidden","이식각",Role.ENGINEER,List.of("ETCH"),"식각기술팀");
  when(users.findByUsername("etch.lee")).thenReturn(Optional.of(account));
  var current=new AuthUser("USR-CMP-01","cmp.park","박연마",Role.ENGINEER,List.of("CMP"),"연마기술팀",null);
  var request=new MockHttpServletRequest();String previous=request.getSession(true).getId();var response=new MockHttpServletResponse();
  try {
   var session=new DemoPersonaController(users,contexts,true).select(current,"csrf",new DemoPersonaController.Selection("etch.lee"),request,response).data();
   assertThat(session.user().username()).isEqualTo("etch.lee");assertThat(session.processScopes()).containsExactly("ETCH");
   assertThat(request.getSession().getId()).isNotEqualTo(previous);
   assertThat(((AuthUser)SecurityContextHolder.getContext().getAuthentication().getPrincipal()).passwordHash()).isNull();
   verify(contexts).saveContext(any(),eq(request),eq(response));
   assertThatThrownBy(()->new DemoPersonaController(users,contexts,true).select(current,"csrf",new DemoPersonaController.Selection("unknown"),request,response)).isInstanceOf(com.fabcontext.api.common.ApiException.class);
  } finally {SecurityContextHolder.clearContext();}
 }
}
