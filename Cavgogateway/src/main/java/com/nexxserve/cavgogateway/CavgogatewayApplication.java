package com.nexxserve.cavgogateway;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.cloud.client.discovery.EnableDiscoveryClient;
import org.springframework.cloud.gateway.route.RouteDefinitionLocator;
import org.springframework.cloud.gateway.route.RouteDefinition;
import org.springframework.context.event.EventListener;
import reactor.core.publisher.Flux;

import java.util.concurrent.atomic.AtomicInteger;

@SpringBootApplication
@EnableDiscoveryClient
public class CavgogatewayApplication {

    private static final Logger logger = LoggerFactory.getLogger(CavgogatewayApplication.class);
    private final RouteDefinitionLocator routeDefinitionLocator;

    public CavgogatewayApplication(RouteDefinitionLocator routeDefinitionLocator) {
        this.routeDefinitionLocator = routeDefinitionLocator;
    }

    public static void main(String[] args) {
        SpringApplication.run(CavgogatewayApplication.class, args);
    }

    @EventListener(ApplicationReadyEvent.class)
    public void onApplicationReady() {
        logger.info("🚀 ================================");
        logger.info("🚀 CAVGO GATEWAY STARTED SUCCESSFULLY");
        logger.info("🚀 Server running on port: 8070");
        logger.info("🚀 CORS enabled for all origins");
        logger.info("🚀 Request/Response logging enabled");
        logger.info("🚀 Management endpoints available at: /actuator");
        logger.info("🚀 ================================");
        printRegisteredRoutes();
    }

    private void printRegisteredRoutes() {
        logger.info("");
        logger.info("🗺️  ================================ REGISTERED ROUTES ================================");
        logger.info("🗺️");

        Flux<RouteDefinition> routes = routeDefinitionLocator.getRouteDefinitions();
        AtomicInteger counter = new AtomicInteger(1);

        routes.doOnNext(route -> {
            int num = counter.getAndIncrement();
            String routeId = route.getId();
            String uri = route.getUri() != null ? route.getUri().toString() : "N/A";

            // Extract path predicate
            String pathPredicate = route.getPredicates().stream()
                    .filter(p -> p.getName().equals("Path"))
                    .findFirst()
                    .flatMap(p -> p.getArgs().values().stream().findFirst())
                    .orElse("N/A");

            // Extract strip prefix count
            long stripPrefixCount = route.getFilters().stream()
                    .filter(f -> f.getName().equals("StripPrefix"))
                    .findFirst()
                    .flatMap(f -> f.getArgs().values().stream().findFirst())
                    .map(Long::parseLong)
                    .orElse(0L);

            // Determine service name from URI
            String serviceName = uri.replace("lb://", "").replace("lb:ws://", "");

            logger.info("🗺️  Route #{}", num);
            logger.info("🗺️    Route ID:   {}", routeId);
            logger.info("🗺️    Gateway Path:  {}  ->  {}[StripPrefix={}]", pathPredicate, uri, stripPrefixCount);
            logger.info("🗺️    Service:     {}", serviceName);
            logger.info("🗺️");
        }).doOnComplete(() -> {
            logger.info("🗺️  ================================ {} TOTAL ROUTES ================================", counter.get() - 1);
            logger.info("🗺️");
        }).blockLast();
    }
}
