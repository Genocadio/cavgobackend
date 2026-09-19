package com.nexxserve.cavgomain.config;

import com.rabbitmq.client.ConnectionFactory;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.net.ConnectException;
import java.net.URI;
import java.net.UnknownHostException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.sql.SQLException;
import java.time.Duration;
import java.util.concurrent.TimeoutException;

/**
 * Boot-time dependency connectivity report. Runs once AFTER the application is
 * ready, so it can never block or break startup. For every dependency cavgomain
 * talks to, logs whether it is reachable — and the failure reason when it is
 * not — so health on boot is visible in the logs instead of silently skipped.
 *
 * <p>Checks are best-effort with short timeouts and fully error-guarded: a
 * failure is only reported, never thrown.
 */
@Slf4j
@Component
public class BootDependencyHealthLogger {

    private final DataSource dataSource;

    private final String nexxauthBaseUrl;
    private final String aggregatorBaseUrl;
    private final String ikuriyeBaseUrl;

    private final String rabbitHost;
    private final int rabbitPort;
    private final String rabbitUser;
    private final String rabbitPass;

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(2))
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();

    public BootDependencyHealthLogger(
            DataSource dataSource,
            @Value("${nexxauth.base-url:}") String nexxauthBaseUrl,
            @Value("${aggregator.base-url:}") String aggregatorBaseUrl,
            @Value("${ikuriye.base-url:}") String ikuriyeBaseUrl,
            @Value("${spring.rabbitmq.host:localhost}") String rabbitHost,
            @Value("${spring.rabbitmq.port:5672}") int rabbitPort,
            @Value("${spring.rabbitmq.username:}") String rabbitUser,
            @Value("${spring.rabbitmq.password:}") String rabbitPass
    ) {
        this.dataSource = dataSource;
        this.nexxauthBaseUrl = normalize(nexxauthBaseUrl);
        this.aggregatorBaseUrl = normalize(aggregatorBaseUrl);
        this.ikuriyeBaseUrl = normalize(ikuriyeBaseUrl);
        this.rabbitHost = rabbitHost;
        this.rabbitPort = rabbitPort;
        this.rabbitUser = rabbitUser;
        this.rabbitPass = rabbitPass;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void onReady() {
        log.info("═══════════════ CAVGOMAIN BOOT DEPENDENCY HEALTH ═══════════════");
        log.info("  Targets: RabbitMQ, PostgreSQL, NeXXauth, Aggregator, Ikuriye");
        check("PostgreSQL", null, "datasource",
                () -> {
                    try (var conn = dataSource.getConnection();
                         var stmt = conn.createStatement();
                         var rs = stmt.executeQuery("SELECT 1")) {
                        return rs.next();
                    } catch (SQLException e) {
                        throw new IllegalStateException(e.getMessage(), e);
                    }
                });
        check("RabbitMQ", rabbitHost + ":" + rabbitPort, "rabbitmq",
                this::rabbitReachable);
        check("NeXXauth", nexxauthBaseUrl, "nexxauth.base-url",
                () -> httpReachable(nexxauthBaseUrl));
        check("Aggregator", aggregatorBaseUrl, "aggregator.base-url",
                () -> httpReachable(aggregatorBaseUrl));
        check("Ikuriye", ikuriyeBaseUrl, "ikuriye.base-url",
                () -> httpReachable(ikuriyeBaseUrl));
        log.info("══════════════════════════════════════════════════════════════");
    }

    private void check(String label, String target, String configKey,
                       java.util.function.BooleanSupplier probe) {
        if (target == null || target.isBlank()) {
            log.warn("  ── {}  SKIPPED ({} not configured)", label, configKey);
            return;
        }
        try {
            if (probe.getAsBoolean()) {
                log.info("  ✓ {}  REACHABLE   ({})", label, target);
            } else {
                log.warn("  ⚠ {}  UNKNOWN ({})", label, target);
            }
        } catch (Exception e) {
            log.error("  ✗ {}  NOT REACHABLE ({}) — reason: {}",
                    label, target, rootMessage(e));
        }
    }

    private boolean rabbitReachable() {
        try {
            ConnectionFactory factory = new ConnectionFactory();
            factory.setHost(rabbitHost);
            factory.setPort(rabbitPort);
            factory.setUsername(rabbitUser);
            factory.setPassword(rabbitPass);
            factory.setConnectionTimeout(3000);
            factory.setHandshakeTimeout(4000);
            try (var conn = factory.newConnection()) {
                return conn.isOpen();
            }
        } catch (Exception e) {
            throw new IllegalStateException(e.getMessage(), e);
        }
    }

    private boolean httpReachable(String baseUrl) {
        try {
            if (baseUrl == null || baseUrl.isBlank()) {
                return false;
            }
            URI uri = URI.create(baseUrl);
            HttpRequest request = HttpRequest.newBuilder(uri)
                    .method("HEAD", HttpRequest.BodyPublishers.noBody())
                    .timeout(Duration.ofSeconds(4))
                    .header("User-Agent", "cavgomain-boot-health")
                    .build();
            HttpResponse<Void> response = http.send(request, HttpResponse.BodyHandlers.discarding());
            // Any HTTP response proves the endpoint is reachable, incl. 4xx/5xx.
            return response.statusCode() > 0;
        } catch (Exception e) {
            throw new IllegalStateException(e.getMessage(), e);
        }
    }

    private static String normalize(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isBlank() ? null : trimmed;
    }

    private static String rootMessage(Throwable t) {
        Throwable cause = t;
        while (cause.getCause() != null) {
            cause = cause.getCause();
        }
        if (cause instanceof UnknownHostException) {
            return "unknown host: " + cause.getMessage();
        }
        if (cause instanceof ConnectException) {
            return "connection refused: " + cause.getMessage();
        }
        if (cause instanceof TimeoutException || cause instanceof java.net.SocketTimeoutException) {
            return "connection timed out";
        }
        return (cause.getClass().getSimpleName() + ": " + cause.getMessage());
    }
}