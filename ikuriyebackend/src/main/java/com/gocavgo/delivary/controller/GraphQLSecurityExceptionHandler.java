package com.gocavgo.delivary.controller;

import com.gocavgo.delivary.exception.BusinessValidationException;
import graphql.GraphQLError;
import graphql.schema.DataFetchingEnvironment;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.graphql.data.method.annotation.GraphQlExceptionHandler;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.Map;

@RestControllerAdvice
public class GraphQLSecurityExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GraphQLSecurityExceptionHandler.class);

    @GraphQlExceptionHandler
    public GraphQLError handleAccessDenied(AccessDeniedException ex, DataFetchingEnvironment env) {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated()) {
            return GraphQLError.newError()
                    .message("Unauthenticated: valid authentication required")
                    .extensions(Map.of("code", "UNAUTHENTICATED"))
                    .build();
        }
        return GraphQLError.newError()
                .message("Forbidden: you do not have permission to perform this action")
                .extensions(Map.of("code", "FORBIDDEN"))
                .build();
    }

    @GraphQlExceptionHandler
    public GraphQLError handleBusinessValidation(BusinessValidationException ex, DataFetchingEnvironment env) {
        log.warn("Business validation error: {}", ex.getMessage());
        return GraphQLError.newError()
                .message(ex.getMessage())
                .extensions(Map.of("code", "VALIDATION_ERROR"))
                .path(env.getExecutionStepInfo().getPath())
                .build();
    }

    @GraphQlExceptionHandler
    public GraphQLError handleRuntime(RuntimeException ex, DataFetchingEnvironment env) {
        log.error("GraphQL runtime error: {}", ex.getMessage(), ex);
        return GraphQLError.newError()
                .message("An unexpected error occurred. Please try again.")
                .extensions(Map.of("code", "INTERNAL_ERROR"))
                .path(env.getExecutionStepInfo().getPath())
                .build();
    }
}
