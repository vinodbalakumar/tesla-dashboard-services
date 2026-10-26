package com.java.vinod.tesla.dashboard.services.diagnostics;

import java.util.Map;
import org.slf4j.*;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import jakarta.servlet.http.HttpServletRequest;

/** Fallback for unhandled application errors; existing domain handlers retain their contracts. */
@RestControllerAdvice
@Order(Ordered.LOWEST_PRECEDENCE)
public class SafeFailureAdvice {
    private static final Logger log = LoggerFactory.getLogger(SafeFailureAdvice.class);
    @ExceptionHandler({org.springframework.web.bind.MethodArgumentNotValidException.class,
            org.springframework.http.converter.HttpMessageNotReadableException.class,
            org.springframework.web.method.annotation.MethodArgumentTypeMismatchException.class,
            jakarta.validation.ConstraintViolationException.class})
    ResponseEntity<?> invalid(Exception ex, HttpServletRequest request) { return error(400, request, ex); }
    @ExceptionHandler(org.springframework.security.access.AccessDeniedException.class)
    ResponseEntity<?> denied(Exception ex, HttpServletRequest request) { return error(403, request, ex); }
    @ExceptionHandler(ResponseStatusException.class)
    ResponseEntity<?> status(ResponseStatusException ex, HttpServletRequest request) {
        return error(ex.getStatusCode().value(), request, ex);
    }
    @ExceptionHandler(Exception.class)
    ResponseEntity<?> unexpected(Exception ex, HttpServletRequest request) { return error(500, request, ex); }
    private ResponseEntity<?> error(int status, HttpServletRequest request, Exception ex) {
        request.setAttribute("diagnosticExceptionType", ex.getClass().getSimpleName());
        String id = String.valueOf(request.getAttribute("X-Correlation-ID"));
        log.warn("application_error correlationId={} status={} exceptionType={}", id, status, ex.getClass().getSimpleName());
        String code = status >= 500 ? "SERVICE_UNAVAILABLE" : status == 404 ? "NOT_FOUND" : status == 403 ? "ACCESS_DENIED" : "REQUEST_REJECTED";
        String message = status >= 500 ? "This service is temporarily unavailable. Please try again shortly."
                : status == 404 ? "The requested item could not be found."
                : status == 403 ? "You do not have permission to perform this action." : "Please check your request and try again.";
        return ResponseEntity.status(status).body(Map.of("code", code, "message", message, "correlationId", id));
    }
}
