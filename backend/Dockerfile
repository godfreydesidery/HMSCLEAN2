# syntax=docker/dockerfile:1
# Multi-stage build for the Zana HMIS backend (ADR-0013).
#
# The build stage uses the official Maven image (system mvn) rather than the Maven wrapper,
# because the .mvn/wrapper files are generated locally via `mvn -N wrapper:wrapper` and are not
# committed from the authoring sandbox (writes to .mvn/ are blocked there). This keeps
# `docker compose up` working without the wrapper present.

# --- Stage 1: build ---
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /workspace
COPY pom.xml ./
COPY lombok.config ./
RUN mvn -B -q -ntp dependency:go-offline
COPY src ./src
RUN mvn -B -q -ntp clean package -DskipTests

# --- Stage 2: runtime ---
FROM eclipse-temurin:21-jre-alpine AS runtime
WORKDIR /app
# Non-root user (UID 1000)
RUN addgroup -S app && adduser -S -u 1000 -G app app
COPY --from=build /workspace/target/hmis-backend.jar /app/app.jar
USER app
EXPOSE 8080
ENV JAVA_OPTS="-XX:+UseContainerSupport -XX:MaxRAMPercentage=75.0"
ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -jar /app/app.jar"]
