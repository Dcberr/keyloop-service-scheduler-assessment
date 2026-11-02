package com.keyloop.scheduler.api;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.UUID;
import java.util.regex.Pattern;
import org.slf4j.MDC;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.servlet.HandlerMapping;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class RequestIdFilter extends OncePerRequestFilter {
    private static final Logger LOG=LoggerFactory.getLogger(RequestIdFilter.class);
    static final String ATTRIBUTE=RequestIdFilter.class.getName()+".requestId";
    private static final Pattern VALID=Pattern.compile("[A-Za-z0-9._-]{1,128}");
    @Override protected void doFilterInternal(HttpServletRequest request,HttpServletResponse response,FilterChain chain) throws ServletException,IOException {
        String supplied=request.getHeader("X-Request-ID");
        String requestId=supplied!=null && VALID.matcher(supplied).matches()?supplied:UUID.randomUUID().toString();
        request.setAttribute(ATTRIBUTE,requestId);response.setHeader("X-Request-ID",requestId);MDC.put("requestId",requestId);
        long started = System.nanoTime();
        boolean completed = false;
        try {
            chain.doFilter(request,response);
            completed = true;
        } finally {
            try {
                Object mappedRoute = request.getAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE);
                String route = mappedRoute instanceof String text ? text : "unmatched";
                LOG.atInfo().addKeyValue("event", "request_complete").addKeyValue("method", request.getMethod())
                        .addKeyValue("route", route).addKeyValue("status", completed ? response.getStatus() : 500)
                        .addKeyValue("elapsedMs", (System.nanoTime() - started) / 1_000_000.0)
                        .log("HTTP request completed");
            } catch (RuntimeException ignored) {
                // Request correlation cleanup and response delivery do not depend on the logger.
            } finally {
                MDC.remove("requestId");
            }
        }
    }
    static String get(HttpServletRequest request) {
        Object value=request.getAttribute(ATTRIBUTE);return value instanceof String text?text:UUID.randomUUID().toString();
    }
}
