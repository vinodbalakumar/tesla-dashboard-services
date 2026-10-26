package com.java.vinod.tesla.dashboard.services.diagnostics;

import ch.qos.logback.classic.*;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.AppenderBase;
import jakarta.annotation.*;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/** Classifies application ERROR logs without copying their messages or arguments to AI. */
@Component
public class DiagnosticLogBridge {
    private final DiagnosticsPublisher diagnostics;
    private AppenderBase<ILoggingEvent> appender;
    private Logger root;
    public DiagnosticLogBridge(DiagnosticsPublisher diagnostics) { this.diagnostics = diagnostics; }
    @PostConstruct public void start() {
        if (!(LoggerFactory.getILoggerFactory() instanceof LoggerContext context)) return;
        root = context.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
        appender = new AppenderBase<>() {
            @Override protected void append(ILoggingEvent event) {
                String logger = event.getLoggerName();
                if (!event.getLevel().isGreaterOrEqual(Level.ERROR) || logger == null || !logger.startsWith("com.java.vinod.")) return;
                String operation = logger.substring(logger.lastIndexOf('.') + 1) + ".logError";
                if (!operation.matches("[A-Za-z0-9_.]{1,180}")) return;
                String id = event.getMDCPropertyMap().get("correlationId");
                if (id == null || !id.matches("[A-Za-z0-9_-]{1,64}")) id = java.util.UUID.randomUUID().toString();
                var cause = event.getThrowableProxy();
                for (int depth = 0; cause != null && cause.getCause() != null && depth < 8; depth++) cause = cause.getCause();
                String category = cause == null ? "LoggedError" : cause.getClassName().substring(cause.getClassName().lastIndexOf('.') + 1);
                if (!category.matches("[A-Za-z0-9_]{1,80}")) category = "LoggedError";
                // Do not call getFormattedMessage(), getArgumentArray() or ThrowableProxy.getMessage().
                diagnostics.publish(id, "OTHER", operation, 500, 0, category);
            }
        };
        appender.setContext(context); appender.setName("sharity-diagnostic-metadata"); appender.start(); root.addAppender(appender);
    }
    @PreDestroy public void close() { if (root != null && appender != null) { root.detachAppender(appender); appender.stop(); } }
}
