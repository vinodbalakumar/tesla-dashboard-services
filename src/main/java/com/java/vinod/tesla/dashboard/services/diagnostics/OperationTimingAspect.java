package com.java.vinod.tesla.dashboard.services.diagnostics;

import io.micrometer.observation.*;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.*;
import org.slf4j.*;
import org.springframework.stereotype.Component;

/** Fixed operation names only; method arguments, results, SQL and exception messages are excluded. */
@Aspect @Component
public class OperationTimingAspect {
    private static final Logger log = LoggerFactory.getLogger(OperationTimingAspect.class);
    private final ObservationRegistry registry;
    private final DiagnosticsPublisher diagnostics;
    private final long slowMs;
    public OperationTimingAspect(ObservationRegistry registry, DiagnosticsPublisher diagnostics,
            @org.springframework.beans.factory.annotation.Value("${app.diagnostics.slow-ms:2000}") long slowMs) {
        this.registry = registry; this.diagnostics = diagnostics; this.slowMs = Math.max(100, slowMs);
    }
    @Around("@within(org.springframework.stereotype.Service) && execution(public * *(..))")
    public Object time(ProceedingJoinPoint point) throws Throwable {
        String operation = point.getSignature().getDeclaringType().getSimpleName() + "." + point.getSignature().getName();
        Observation observation = Observation.createNotStarted("sharity.operation", registry)
                .lowCardinalityKeyValue("operation", operation).start();
        long start = System.nanoTime();
        String failure = "NONE";
        int status = 200;
        try (var ignored = observation.openScope()) {
            try { return point.proceed(); }
            catch (Throwable ex) {
                failure = ex.getClass().getSimpleName();
                status = ex instanceof org.springframework.web.ErrorResponse error ? error.getStatusCode().value()
                        : ex instanceof org.springframework.security.access.AccessDeniedException ? 403 : 500;
                // Record only the exception category, avoiding messages in exported span events.
                observation.lowCardinalityKeyValue("outcome", "error");
                log.warn("operation_failed operation={} correlationId={} exceptionType={}", operation,
                        MDC.get("correlationId"), ex.getClass().getSimpleName());
                throw ex;
            } finally {
                long ms = (System.nanoTime() - start) / 1_000_000;
                if (ms >= slowMs) log.warn("slow_operation operation={} correlationId={} durationMs={}", operation, MDC.get("correlationId"), ms);
                if ((status >= 500 || ms >= slowMs) && operation.matches("[A-Za-z0-9_.]{1,180}")) {
                    String id = MDC.get("correlationId");
                    if (id == null || !id.matches("[A-Za-z0-9_-]{1,64}")) id = java.util.UUID.randomUUID().toString();
                    diagnostics.publish(id, "OTHER", operation, status, ms,
                            failure.matches("[A-Za-z0-9_]{1,80}") ? failure : "UnknownException");
                }
                observation.stop();
            }
        }
    }
}
