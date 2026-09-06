package com.nexxserve.cavgogateway.filter;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.http.server.reactive.ServerHttpRequestDecorator;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.http.server.reactive.ServerHttpResponseDecorator;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

/**
 * Logs request/response headers and bodies for every request. Body logging
 * buffers whole payloads (MB-size GraphQL responses included) and serialises
 * them per request — expensive. Controlled by GATEWAY_LOG_BODIES / 
 * GATEWAY_LOG_HEADERS env vars (default: off in production).
 */
@Component
public class GlobalLoggingFilter implements GlobalFilter, Ordered {

    private static final Logger logger = LoggerFactory.getLogger(GlobalLoggingFilter.class);

    private static final boolean LOG_BODIES = Boolean.parseBoolean(
            System.getenv().getOrDefault("GATEWAY_LOG_BODIES", "false"));
    private static final boolean LOG_HEADERS = Boolean.parseBoolean(
            System.getenv().getOrDefault("GATEWAY_LOG_HEADERS", "false"));

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        String requestId = UUID.randomUUID().toString();
        ServerHttpRequest request = exchange.getRequest();

        // Check if this is an SSE or file-upload request
        boolean isSSERequest = isServerSentEventRequest(request);
        boolean isMultipartUpload = isMultipartUpload(request);

        if (logger.isInfoEnabled()) {
            logger.info("🔵 [GATEWAY-REQUEST-{}] {} {}", requestId, request.getMethod(), request.getPath());
        }

        // Skip body buffering/decoration entirely unless body logging is enabled.
        if (!LOG_BODIES) {
            return chain.filter(exchange)
                    .doOnSuccess(aVoid -> {
                        if (isSSERequest) {
                            logger.info("🟢 [SSE-SUCCESS-{}] SSE Connection established successfully", requestId);
                        } else {
                            logger.info("🟢 [GATEWAY-SUCCESS-{}] {} {} -> {}", requestId,
                                    request.getMethod(), request.getPath(), exchange.getResponse().getStatusCode());
                        }
                    })
                    .doOnError(error ->
                            logger.error("🔴 [GATEWAY-ERROR-{}] {} {} failed: {}", requestId,
                                    request.getMethod(), request.getPath(), error.getMessage()));
        }

        // Decorate request to log body if present and not SSE / file upload
        ServerHttpRequestDecorator requestDecorator = new ServerHttpRequestDecorator(request) {
            @Override
            public Flux<DataBuffer> getBody() {
                if (isSSERequest) {
                    // For SSE, just log that body is present but don't read it
                    return super.getBody().doOnNext(dataBuffer -> {
                        logger.info("🔵 [SSE-REQUEST-{}] Body present (SSE - not logging content)", requestId);
                    });
                } else if (isMultipartUpload) {
                    // For file uploads, stream body through without reading.
                    // Reading multipart binary data into a String triggers the
                    // codec max-in-memory-size limit and causes HTTP 413 errors.
                    return super.getBody();
                } else {
                    return super.getBody().doOnNext(dataBuffer -> {
                        byte[] bytes = new byte[dataBuffer.readableByteCount()];
                        dataBuffer.read(bytes);
                        dataBuffer.readPosition(0); // Reset position for actual processing
                        String body = new String(bytes, StandardCharsets.UTF_8);
                        logger.info("🔵 [GATEWAY-REQUEST-{}] Body: {}", requestId, body);
                    });
                }
            }
        };

        // Decorate response to log response details
        ServerHttpResponseDecorator responseDecorator = new ServerHttpResponseDecorator(exchange.getResponse()) {
            @Override
            public Mono<Void> writeWith(org.reactivestreams.Publisher<? extends DataBuffer> body) {
                if (isSSERequest) {
                    // For SSE, log connection establishment but not the streaming content
                    logger.info("🟡 [SSE-RESPONSE-{}] SSE Connection established, streaming started", requestId);
                    return super.writeWith(Flux.from(body).doOnNext(dataBuffer -> {
                        // Just log that data is streaming, don't log content to avoid spam
                        logger.debug("🟡 [SSE-RESPONSE-{}] SSE data chunk sent", requestId);
                    }));
                } else {
                    return super.writeWith(Flux.from(body).doOnNext(dataBuffer -> {
                        byte[] bytes = new byte[dataBuffer.readableByteCount()];
                        dataBuffer.read(bytes);
                        dataBuffer.readPosition(0); // Reset position for actual processing
                        String responseBody = new String(bytes, StandardCharsets.UTF_8);
                        logResponse(requestId, getDelegate(), responseBody, false);
                    }));
                }
            }
        };

        ServerWebExchange mutatedExchange = exchange.mutate()
                .request(requestDecorator)
                .response(responseDecorator)
                .build();

        return chain.filter(mutatedExchange)
                .doOnSuccess(aVoid -> {
                    if (isSSERequest) {
                        logger.info("🟢 [SSE-SUCCESS-{}] SSE Connection established successfully", requestId);
                    } else {
                        logger.info("🟢 [GATEWAY-SUCCESS-{}] Request completed successfully", requestId);
                    }
                })
                .doOnError(error -> {
                    if (isSSERequest) {
                        logger.error("🔴 [SSE-ERROR-{}] SSE Connection failed: {}", requestId, error.getMessage());
                    } else {
                        logger.error("🔴 [GATEWAY-ERROR-{}] Request failed: {}", requestId, error.getMessage());
                    }
                });
    }

    private boolean isServerSentEventRequest(ServerHttpRequest request) {
        // Check if request is for SSE endpoint
        String path = request.getPath().value();
        boolean isEventPath = path.contains("/events/");

        // Check Accept header
        String acceptHeader = request.getHeaders().getFirst(HttpHeaders.ACCEPT);
        boolean acceptsSSE = acceptHeader != null && acceptHeader.contains("text/event-stream");

        // Check if it's a POST to subscribe endpoint (your use case)
        boolean isSubscribePost = "POST".equals(request.getMethod().name()) && path.contains("/subscribe");

        return isEventPath || acceptsSSE || isSubscribePost;
    }

    private boolean isMultipartUpload(ServerHttpRequest request) {
        String contentType = request.getHeaders().getFirst(HttpHeaders.CONTENT_TYPE);
        return contentType != null && contentType.toLowerCase().startsWith("multipart/");
    }

    private void logRequest(String requestId, ServerHttpRequest request, boolean isSSE) {
        String prefix = isSSE ? "🔵 [SSE-REQUEST-" : "🔵 [GATEWAY-REQUEST-";
        logger.info("{}{}} =================================", prefix, requestId);
        logger.info("{}{}} Method: {}", prefix, requestId, request.getMethod());
        logger.info("{}{}} URI: {}", prefix, requestId, request.getURI());
        logger.info("{}{}} Path: {}", prefix, requestId, request.getPath());
        logger.info("{}{}} Query: {}", prefix, requestId, request.getQueryParams());
        logger.info("{}{}} Remote Address: {}", prefix, requestId, request.getRemoteAddress());

        if (!LOG_HEADERS) {
            return;
        }

        // Log headers
        HttpHeaders headers = request.getHeaders();
        logger.info("{}{}} Headers:", prefix, requestId);
        headers.forEach((key, values) -> {
            logger.info("{}{}}   {}: {}", prefix, requestId, key, values);
        });

        // Log CORS specific headers
        if (headers.getOrigin() != null) {
            logger.info("{}{}} CORS Origin: {}", prefix, requestId, headers.getOrigin());
        }
        if (headers.getAccessControlRequestMethod() != null) {
            logger.info("{}{}} CORS Method: {}", prefix, requestId, headers.getAccessControlRequestMethod());
        }
        if (headers.getAccessControlRequestHeaders() != null) {
            logger.info("{}{}} CORS Headers: {}", prefix, requestId, headers.getAccessControlRequestHeaders());
        }
    }

    private void logResponse(String requestId, ServerHttpResponse response, String body, boolean isSSE) {
        String prefix = isSSE ? "🟡 [SSE-RESPONSE-" : "🟡 [GATEWAY-RESPONSE-";
        logger.info("{}{}} =================================", prefix, requestId);
        logger.info("{}{}} Status: {}", prefix, requestId, response.getStatusCode());

        // Log response headers
        if (!LOG_HEADERS) {
            return;
        }
        HttpHeaders headers = response.getHeaders();
        logger.info("{}{}} Headers:", prefix, requestId);
        headers.forEach((key, values) -> {
            logger.info("{}{}}   {}: {}", prefix, requestId, key, values);
        });

        // Log CORS response headers
        if (headers.getAccessControlAllowOrigin() != null) {
            logger.info("{}{}} CORS Allow Origin: {}", prefix, requestId, headers.getAccessControlAllowOrigin());
        }
        if (headers.getAccessControlAllowMethods() != null) {
            logger.info("{}{}} CORS Allow Methods: {}", prefix, requestId, headers.getAccessControlAllowMethods());
        }
        if (headers.getAccessControlAllowHeaders() != null) {
            logger.info("{}{}} CORS Allow Headers: {}", prefix, requestId, headers.getAccessControlAllowHeaders());
        }

        // Log response body (truncated if too long)
        if (body != null && !body.isEmpty() && !isSSE) {
            String truncatedBody = body.length() > 1000 ? body.substring(0, 1000) + "... (truncated)" : body;
            logger.info("{}{}} Body: {}", prefix, requestId, truncatedBody);
        }
    }

    @Override
    public int getOrder() {
        return -1; // High priority to log before other filters
    }
}