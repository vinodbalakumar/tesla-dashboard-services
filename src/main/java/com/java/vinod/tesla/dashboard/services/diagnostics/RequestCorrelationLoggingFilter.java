package com.java.vinod.tesla.dashboard.services.diagnostics;

import jakarta.servlet.*;
import jakarta.servlet.http.*;
import java.io.IOException;
import java.util.UUID;
import org.slf4j.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.servlet.HandlerMapping;

/** Request metadata only: never log query strings, bodies, credentials or concrete resource IDs. */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class RequestCorrelationLoggingFilter extends OncePerRequestFilter {
    private static final Logger log = LoggerFactory.getLogger(RequestCorrelationLoggingFilter.class);
    public static final String CORRELATION_ID_ATTRIBUTE = "X-Correlation-ID";
    @Autowired(required = false) private DiagnosticsPublisher diagnostics;

    @Override protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                              FilterChain chain) throws ServletException, IOException {
        String incoming = request.getHeader(CORRELATION_ID_ATTRIBUTE);
        String id = incoming != null && incoming.trim().matches("[A-Za-z0-9_-]{1,64}")
                ? incoming.trim() : UUID.randomUUID().toString();
        request.setAttribute(CORRELATION_ID_ATTRIBUTE, id);
        response.setHeader(CORRELATION_ID_ATTRIBUTE, id);
        long start = System.nanoTime();
        boolean failed = false;
        try (var ignored = MDC.putCloseable("correlationId", id)) {
            try { chain.doFilter(request, response); }
            catch (ServletException | IOException | RuntimeException ex) {
                failed = true;
                request.setAttribute("diagnosticExceptionType", ex.getClass().getSimpleName());
                // Exception messages may contain SQL, tokens or customer content.
                log.error("request_exception correlationId={} exceptionType={}", id, ex.getClass().getSimpleName());
                throw ex;
            } finally {
                long ms = (System.nanoTime() - start) / 1_000_000;
                int status = failed ? 500 : response.getStatus();
                Object pattern = request.getAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE);
                String route = pattern == null ? "unmapped" : pattern.toString();
                if (!route.matches("[A-Za-z0-9/{}_*.-]{1,180}")) route = "unmapped";
                String method = request.getMethod().matches("GET|POST|PUT|PATCH|DELETE|HEAD|OPTIONS") ? request.getMethod() : "OTHER";
                log.info("request_completed correlationId={} method={} route={} status={} durationMs={}", id, method, route, status, ms);
                if (diagnostics != null && !request.getRequestURI().contains("/actuator/")
                        && !request.getRequestURI().contains("/diagnostics")) {
                    Object exception = request.getAttribute("diagnosticExceptionType");
                    String type = exception != null && exception.toString().matches("[A-Za-z0-9_]{1,80}") ? exception.toString() : "NONE";
                    diagnostics.publish(id, method, route, status, ms, type);
                }
            }
        }
    }
}
