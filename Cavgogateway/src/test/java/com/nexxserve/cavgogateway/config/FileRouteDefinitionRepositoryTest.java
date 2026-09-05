package com.nexxserve.cavgogateway.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.cloud.gateway.handler.predicate.PredicateDefinition;
import org.springframework.cloud.gateway.route.RouteDefinition;
import reactor.core.publisher.Mono;

import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class FileRouteDefinitionRepositoryTest {

    @TempDir
    Path tempDir;

    private FileRouteDefinitionRepository newRepo() {
        return new FileRouteDefinitionRepository(tempDir.resolve("routes.json").toString());
    }

    private RouteDefinition route(String id, String path) {
        RouteDefinition definition = new RouteDefinition();
        definition.setId(id);
        definition.setUri(URI.create("lb://cavgomain"));
        PredicateDefinition predicate = new PredicateDefinition();
        predicate.setName("Path");
        predicate.getArgs().put("pattern", path);
        definition.getPredicates().add(predicate);
        return definition;
    }

    @Test
    void createsEmptyFileOnStartup() throws Exception {
        FileRouteDefinitionRepository repo = newRepo();
        assertThat(Files.exists(tempDir.resolve("routes.json"))).isTrue();
        assertThat(repo.getRouteDefinitions().collectList().block()).isEmpty();
    }

    @Test
    void savedRouteIsPersistedAndSurvivesRestart() {
        FileRouteDefinitionRepository repo = newRepo();
        repo.save(Mono.just(route("test-route", "/test/**"))).block();

        // Simulate a restart: a brand-new repository instance reading the same file
        FileRouteDefinitionRepository restarted = newRepo();
        List<RouteDefinition> routes = restarted.getRouteDefinitions().collectList().block();

        assertThat(routes).hasSize(1);
        RouteDefinition loaded = routes.get(0);
        assertThat(loaded.getId()).isEqualTo("test-route");
        assertThat(loaded.getUri().toString()).isEqualTo("lb://cavgomain");
        assertThat(loaded.getPredicates()).hasSize(1);
        assertThat(loaded.getPredicates().get(0).getArgs()).containsEntry("pattern", "/test/**");
    }

    @Test
    void externalFileEditsArePickedUpOnNextRead() throws Exception {
        FileRouteDefinitionRepository repo = newRepo();
        repo.save(Mono.just(route("a", "/a/**"))).block();
        repo.save(Mono.just(route("b", "/b/**"))).block();

        // Manually edit the file (equivalent to editing routes.json + POST /actuator/gateway/refresh)
        String json = """
                [
                  {
                    "id": "edited",
                    "uri": "lb://ridehail",
                    "predicates": [
                      {"name": "Path", "args": {"pattern": "/edited/**"}}
                    ],
                    "filters": [],
                    "metadata": {},
                    "order": 0
                  }
                ]
                """;
        Files.writeString(tempDir.resolve("routes.json"), json);

        List<RouteDefinition> routes = repo.getRouteDefinitions().collectList().block();
        assertThat(routes).hasSize(1);
        assertThat(routes.get(0).getId()).isEqualTo("edited");
        assertThat(routes.get(0).getUri().toString()).isEqualTo("lb://ridehail");
    }

    @Test
    void deleteRemovesRouteAndPersists() {
        FileRouteDefinitionRepository repo = newRepo();
        repo.save(Mono.just(route("keep", "/keep/**"))).block();
        repo.save(Mono.just(route("drop", "/drop/**"))).block();

        repo.delete(Mono.just("drop")).block();

        List<RouteDefinition> routes = repo.getRouteDefinitions().collectList().block();
        assertThat(routes).hasSize(1);
        assertThat(routes.get(0).getId()).isEqualTo("keep");
    }

    @Test
    void savingExistingIdReplacesRoute() {
        FileRouteDefinitionRepository repo = newRepo();
        repo.save(Mono.just(route("dup", "/first/**"))).block();
        repo.save(Mono.just(route("dup", "/second/**"))).block();

        List<RouteDefinition> routes = repo.getRouteDefinitions().collectList().block();
        assertThat(routes).hasSize(1);
        assertThat(routes.get(0).getPredicates().get(0).getArgs()).containsEntry("pattern", "/second/**");
    }
}