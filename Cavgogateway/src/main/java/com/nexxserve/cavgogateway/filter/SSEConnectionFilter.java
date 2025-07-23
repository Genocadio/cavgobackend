package com.nexxserve.cavgogateway.filter;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.http.HttpHeaders;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

@Component
public class SSEConnectionFilter implements GlobalFilter, Ordered {

    private static final Logger logger = LoggerFactory.getLogger(SSEConnectionFilter.class);

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        ServerHttpRequest request = exchange.getRequest();

        // Check if this is an SSE-related request
        if (isSSERequest(request)) {
            logger.info("🔌 [SSE-FILTER] Detected SSE request: {}", request.getURI());

            // Add/modify headers for better SSE handling
            ServerHttpRequest modifiedRequest = exchange.getRequest().mutate()
                    .header("Connection", "keep-alive")
                    .header("Cache-Control", "no-cache")
                    .build();

            exchange = exchange.mutate().request(modifiedRequest).build();

            ServerWebExchange finalExchange = exchange;
            return chain.filter(exchange)
                    .doOnSuccess(aVoid -> {
                        ServerHttpResponse response = finalExchange.getResponse();
                        logger.info("🔌 [SSE-FILTER] SSE Response Status: {}", response.getStatusCode());

                        // Log response headers to debug SSE setup
                        HttpHeaders responseHeaders = response.getHeaders();
                        responseHeaders.forEach((key, values) -> {
                            logger.info("🔌 [SSE-FILTER] Response Header - {}: {}", key, values);
                        });
                    })
                    .doOnError(error -> {
                        logger.error("🔌 [SSE-FILTER] SSE request error: {}", error.getMessage(), error);
                    });
        }

        return chain.filter(exchange);
    }

    private boolean isSSERequest(ServerHttpRequest request) {
        String path = request.getPath().value();
        String acceptHeader = request.getHeaders().getFirst(HttpHeaders.ACCEPT);

        // Check for SSE indicators
        boolean isEventPath = path.contains("/events/");
        boolean acceptsSSE = acceptHeader != null && acceptHeader.contains("text/event-stream");
        boolean isSubscribePost = "POST".equals(request.getMethod().name()) &&
                (path.contains("/subscribe") || path.contains("/events/"));

        return isEventPath || acceptsSSE || isSubscribePost;
    }

    @Override
    public int getOrder() {
        return -2; // Run before the logging filter
    }
}