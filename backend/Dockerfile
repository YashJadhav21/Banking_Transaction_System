# syntax=docker/dockerfile:1
# Build context is the repository root (docker-compose sets it), because the
# SQL migrations live in the top-level db/ folder and are packaged into the jar.

# ---- build stage -------------------------------------------------------------
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /workspace/backend

# Resolve dependencies first so they are cached across source-only changes.
COPY backend/pom.xml ./
RUN --mount=type=cache,target=/root/.m2 mvn -B -q dependency:go-offline

COPY backend/src ./src
COPY db ../db
RUN --mount=type=cache,target=/root/.m2 mvn -B -q package -DskipTests

# ---- runtime stage -----------------------------------------------------------
FROM eclipse-temurin:21-jre
RUN groupadd --system app && useradd --system --gid app --uid 10001 app
WORKDIR /app
COPY --from=build /workspace/backend/target/banking-transaction-system.jar app.jar
USER app
EXPOSE 8080
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-jar", "/app/app.jar"]
