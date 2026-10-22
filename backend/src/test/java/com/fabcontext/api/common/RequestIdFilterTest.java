package com.fabcontext.api.common;

import static org.assertj.core.api.Assertions.*;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class RequestIdFilterTest {
 @Test void requestIdMatchesResponseAndDoesNotLeakToNextRequest() throws Exception {
  String previous=MDC.get("requestId");
  MDC.put("requestId","outer");
  try {
   var response=new MockHttpServletResponse();
   new RequestIdFilter().doFilter(new MockHttpServletRequest("POST","/api/test"),response,(req,res)->{
    assertThat(MDC.get("requestId")).startsWith("req_").isEqualTo(req.getAttribute(RequestIdFilter.ATTRIBUTE));
   });
   assertThat(response.getHeader(RequestIdFilter.HEADER)).startsWith("req_");
   assertThat(MDC.get("requestId")).isEqualTo("outer");
  } finally { if(previous==null) MDC.remove("requestId"); else MDC.put("requestId",previous); }
 }
 @Test void exceptionAlsoCleansMdc() {
  String previous=MDC.get("requestId"); MDC.remove("requestId");
  try {
   assertThatThrownBy(()->new RequestIdFilter().doFilter(new MockHttpServletRequest(),new MockHttpServletResponse(),(req,res)->{throw new jakarta.servlet.ServletException("test");}))
    .isInstanceOf(jakarta.servlet.ServletException.class);
   assertThat(MDC.get("requestId")).isNull();
  } finally { if(previous!=null) MDC.put("requestId",previous); }
 }
}
