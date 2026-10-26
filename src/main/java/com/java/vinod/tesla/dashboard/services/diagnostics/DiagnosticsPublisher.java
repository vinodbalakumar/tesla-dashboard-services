package com.java.vinod.tesla.dashboard.services.diagnostics;

import jakarta.annotation.PreDestroy;
import java.net.URI;
import java.net.http.*;
import java.time.Duration;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** Bounded best-effort delivery, separate from the request thread. Never retries customer operations. */
@Component
public class DiagnosticsPublisher {
    private static final Logger log = LoggerFactory.getLogger(DiagnosticsPublisher.class);
    private final String service, token;
    private final URI endpoint;
    private final boolean enabled;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
    private final AtomicLong dropped = new AtomicLong();
    private final ThreadPoolExecutor sender = new ThreadPoolExecutor(1, 1, 30, TimeUnit.SECONDS,
            new ArrayBlockingQueue<>(128), task -> { var t = new Thread(task, "diagnostics-sender"); t.setDaemon(true); return t; },
            new ThreadPoolExecutor.AbortPolicy());

    public DiagnosticsPublisher(@Value("${spring.application.name:tesla-dashboard-services}") String service,
            @Value("${app.diagnostics.enabled:false}") boolean enabled,
            @Value("${app.diagnostics.url:http://localhost:8086/internal/diagnostics/events}") String url,
            @Value("${app.diagnostics.token:}") String token) {
        this.service = "tesla-dashboard-services"; // Stable allowlisted identity, independent of display name.
        this.endpoint = URI.create(url);
        this.token = token;
        this.enabled = enabled && token.length() >= 32;
        if (enabled && !this.enabled) throw new IllegalArgumentException("Diagnostics requires a token of at least 32 characters");
        if (enabled && !("https".equals(endpoint.getScheme()) || ("http".equals(endpoint.getScheme())
                && java.util.Set.of("localhost", "127.0.0.1", "::1").contains(endpoint.getHost())))) {
            throw new IllegalArgumentException("Diagnostics requires HTTPS outside loopback");
        }
    }
    public void publish(String id, String method, String route, int status, long durationMs, String exceptionType) {
        if (!enabled) return;
        String json = "{\"service\":\"" + service + "\",\"correlationId\":\"" + id
                + "\",\"method\":\"" + method + "\",\"route\":\"" + route
                + "\",\"status\":" + status + ",\"durationMs\":" + durationMs + ",\"exceptionType\":\"" + exceptionType + "\"}";
        try { sender.execute(() -> {
            try {
                var result = http.send(HttpRequest.newBuilder(endpoint).timeout(Duration.ofSeconds(3))
                        .header("Content-Type", "application/json").header("X-Diagnostics-Token", token)
                        .POST(HttpRequest.BodyPublishers.ofString(json)).build(), HttpResponse.BodyHandlers.discarding());
                if (result.statusCode() != 202) drop();
            } catch (InterruptedException ex) { Thread.currentThread().interrupt(); drop(); }
            catch (Exception ex) { drop(); }
        }); } catch (RejectedExecutionException ex) { drop(); }
    }
    private void drop() {
        long count = dropped.incrementAndGet();
        if (count == 1 || count % 100 == 0) log.warn("diagnostics_delivery_dropped service={} total={}", service, count);
    }
    @PreDestroy public void close() { sender.shutdownNow(); http.close(); }
}
