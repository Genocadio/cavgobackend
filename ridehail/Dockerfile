# Multi-stage build for optimal caching and small image size
FROM gradle:8-jdk21-alpine AS build

WORKDIR /app

COPY build.gradle settings.gradle ./
COPY gradle/ gradle/
COPY src/ src/

RUN gradle build --no-daemon -x test

FROM eclipse-temurin:21-jre-alpine

RUN apk add --no-cache wget

RUN addgroup -g 1001 -S spring && \
    adduser -u 1001 -S spring -G spring

WORKDIR /app

COPY --from=build /app/build/libs/*.jar app.jar

RUN chown -R spring:spring /app
USER spring

EXPOSE 6070

HEALTHCHECK --interval=30s --timeout=10s --start-period=60s --retries=3 \
  CMD wget --no-verbose --tries=1 --spider http://localhost:6070/actuator/health || exit 1

ENTRYPOINT ["java", "-jar", "app.jar"]





