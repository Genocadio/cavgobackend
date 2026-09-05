package com.nexxserve.cavgogateway.config;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cloud.gateway.route.RouteDefinition;
import org.springframework.cloud.gateway.route.RouteDefinitionRepository;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * File-backed {@link RouteDefinitionRepository} that lets you manage gateway
 * routes at runtime without restarting the gateway.
 *
 * <p>Routes are stored as JSON in a single file (default {@code ./routes.json},
 * override with {@code APP_ROUTES_FILE} / {@code app.routes.file}). Every read
 * re-reads the file, so:
 * <ul>
 *   <li>editing the file and calling {@code POST /actuator/gateway/refresh}
 *       applies the change without a restart, and</li>
 *   <li>routes added via {@code POST /actuator/gateway/routes/{id}} are written
 *       through to the file and survive restarts (unlike the default in-memory
 *       repository).</li>
 * </ul>
 *
 * <p>Because this bean replaces Spring Cloud Gateway's in-memory
 * {@code RouteDefinitionRepository}, the actuator write endpoints
 * ({@code POST}/{@code DELETE} {@code /actuator/gateway/routes/{id}}) persist to
 * this file instead of memory.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE) // file routes are consulted before YAML routes
public class FileRouteDefinitionRepository implements RouteDefinitionRepository {

    private static final Logger log = LoggerFactory.getLogger(FileRouteDefinitionRepository.class);
    private static final TypeReference<List<RouteDefinition>> ROUTES_TYPE = new TypeReference<>() {
    };

    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
    private final Path file;

    public FileRouteDefinitionRepository(@Value("${app.routes.file:./routes.json}") String filePath) {
        this.file = Paths.get(filePath).toAbsolutePath().normalize();
        ensureFile();
    }

    @Override
    public Flux<RouteDefinition> getRouteDefinitions() {
        try {
            return Flux.fromIterable(readRoutes());
        } catch (IOException e) {
            log.error("Failed to read route definitions from {}: {}", file, e.getMessage());
            return Flux.empty();
        }
    }

    @Override
    public Mono<Void> save(Mono<RouteDefinition> route) {
        return route.flatMap(definition -> {
            synchronized (this) {
                try {
                    List<RouteDefinition> routes = readRoutes();
                    upsert(routes, definition);
                    writeRoutes(routes);
                    log.info("Route '{}' saved to {} ({} total routes)", definition.getId(), file, routes.size());
                    return Mono.empty();
                } catch (IOException e) {
                    log.error("Failed to save route '{}' to {}: {}", definition.getId(), file, e.getMessage());
                    return Mono.error(e);
                }
            }
        });
    }

    @Override
    public Mono<Void> delete(Mono<String> routeId) {
        return routeId.flatMap(id -> {
            synchronized (this) {
                try {
                    List<RouteDefinition> routes = readRoutes();
                    boolean removed = routes.removeIf(r -> id.equals(r.getId()));
                    if (removed) {
                        writeRoutes(routes);
                        log.info("Route '{}' deleted from {} ({} routes remain)", id, file, routes.size());
                    } else {
                        log.warn("Route '{}' not found in {}, nothing deleted", id, file);
                    }
                    return Mono.empty();
                } catch (IOException e) {
                    log.error("Failed to delete route '{}' from {}: {}", id, file, e.getMessage());
                    return Mono.error(e);
                }
            }
        });
    }

    public Path getFile() {
        return file;
    }

    // ------------------------------------------------------------------
    // internals
    // ------------------------------------------------------------------

    private void ensureFile() {
        try {
            if (Files.notExists(file)) {
                Path parent = file.getParent();
                if (parent != null) {
                    Files.createDirectories(parent);
                }
                Files.writeString(file, "[]", StandardCharsets.UTF_8);
                log.info("Created empty route definitions file at {}", file);
            }
        } catch (IOException e) {
            log.error("Failed to create route definitions file at {}: {}", file, e.getMessage());
        }
    }

    private List<RouteDefinition> readRoutes() throws IOException {
        if (Files.notExists(file)) {
            return new ArrayList<>();
        }
        String json = Files.readString(file, StandardCharsets.UTF_8);
        List<RouteDefinition> routes = objectMapper.readValue(json, ROUTES_TYPE);
        return routes != null ? routes : new ArrayList<>();
    }

    private void writeRoutes(List<RouteDefinition> routes) throws IOException {
        String json = objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(routes);
        Files.writeString(file, json, StandardCharsets.UTF_8);
    }

    private void upsert(List<RouteDefinition> routes, RouteDefinition definition) {
        Optional<RouteDefinition> existing = routes.stream()
                .filter(r -> definition.getId().equals(r.getId()))
                .findFirst();
        if (existing.isPresent()) {
            existing.get().setUri(definition.getUri());
            existing.get().setPredicates(definition.getPredicates());
            existing.get().setFilters(definition.getFilters());
            existing.get().setMetadata(definition.getMetadata());
            existing.get().setOrder(definition.getOrder());
        } else {
            routes.add(definition);
        }
    }
}