package com.nexxserve.cavgogateway.filter;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.cloud.gateway.route.Route;
import org.springframework.core.Ordered;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.net.URI;

import static org.springframework.cloud.gateway.support.ServerWebExchangeUtils.GATEWAY_ROUTE_ATTR;

@Component
public class RouteLoggingFilter implements GlobalFilter, Ordered {

    private static final Logger logger = LoggerFactory.getLogger(RouteLoggingFilter.class);

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        return chain.filter(exchange).doOnSuccess(aVoid -> {
            Route route = exchange.getAttribute(GATEWAY_ROUTE_ATTR);
            if (route != null) {
                URI targetUri = exchange.getRequest().getURI();
                String routeId = route.getId();
                URI routeUri = route.getUri();

                logger.info("🚀 [ROUTE-INFO] Route ID: {} | Original URI: {} | Target URI: {}",
                        routeId, targetUri, routeUri);
                logger.info("🚀 [ROUTE-INFO] Route Predicates: {}", route.getPredicate());
                logger.info("🚀 [ROUTE-INFO] Route Filters: {}", route.getFilters());

                // Determine if response is from gateway or service
                boolean isFromGateway = isGatewayResponse(exchange);
                logger.info("🚀 [ROUTE-INFO] Response Source: {}",
                        isFromGateway ? "GATEWAY" : "DOWNSTREAM_SERVICE");
            }
        });
    }

    private boolean isGatewayResponse(ServerWebExchange exchange) {
        // Check if response was handled by gateway (e.g., CORS preflight, errors)
        return exchange.getResponse().getStatusCode() != null &&
                (exchange.getResponse().getStatusCode().value() == 200 &&
                        exchange.getRequest().getMethod().name().equals("OPTIONS"));
    }

    @Override
    public int getOrder() {
        return Ordered.LOWEST_PRECEDENCE;
    }
}