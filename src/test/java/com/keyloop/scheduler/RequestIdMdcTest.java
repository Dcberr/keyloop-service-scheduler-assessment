package com.keyloop.scheduler;

import com.keyloop.scheduler.api.RequestIdFilter;
import jakarta.servlet.ServletException;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import static org.assertj.core.api.Assertions.*;

class RequestIdMdcTest {
    @Test void requestContextIsClearedAfterNormalCompletion()throws Exception{
        var request=new MockHttpServletRequest();request.addHeader("X-Request-ID","qa-request");var response=new MockHttpServletResponse();
        new RequestIdFilter().doFilter(request,response,(req,res)->assertThat(MDC.get("requestId")).isEqualTo("qa-request"));
        assertThat(response.getHeader("X-Request-ID")).isEqualTo("qa-request");assertThat(MDC.get("requestId")).isNull();
    }
    @Test void requestContextIsClearedWhenDownstreamThrows(){
        var request=new MockHttpServletRequest();request.addHeader("X-Request-ID","qa-failure");
        assertThatThrownBy(()->new RequestIdFilter().doFilter(request,new MockHttpServletResponse(),(req,res)->{assertThat(MDC.get("requestId")).isEqualTo("qa-failure");throw new ServletException("Controlled failure");})).isInstanceOf(ServletException.class);
        assertThat(MDC.get("requestId")).isNull();
    }
}
