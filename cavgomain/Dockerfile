# Multi-stage build for optimal caching and small image size
FROM gradle:8-jdk21-alpine AS build

# Set working directory
WORKDIR /app

# Copy build files first for better caching
COPY build.gradle settings.gradle ./
COPY gradle/ gradle/

# Copy source code
COPY src/ src/

# Build the application
RUN gradle build --no-daemon -x test

# Runtime stage - use Eclipse Temurin JRE 21
FROM eclipse-temurin:21-jre-alpine

# Install wget for health checks
RUN apk add --no-cache wget

# Add non-root user for security
RUN addgroup -g 1001 -S spring && \
    adduser -u 1001 -S spring -G spring

# Set working directory
WORKDIR /app

# Copy the built jar from build stage
COPY --from=build /app/build/libs/*.jar app.jar

# Change ownership to spring user
RUN chown -R spring:spring /app
USER spring

# Expose port
EXPOSE 6060

# Health check
HEALTHCHECK --interval=30s --timeout=10s --start-period=60s --retries=3 \
  CMD wget --no-verbose --tries=1 --spider http://localhost:6060/actuator/health || exit 1

# Run the application
ENTRYPOINT ["java", "-jar", "app.jar"]