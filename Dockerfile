# syntax=docker/dockerfile:1
#
# One parameterised Dockerfile builds all three services.
#
# A Maven multi-module reactor cannot be built module-by-module from separate
# contexts (each module depends on the shared ones), so the whole repository is
# the build context and MODULE selects which artefact ends up in the image. The
# alternative - three near-identical Dockerfiles - would be three places to
# forget a security setting.
#
# Usage:  docker build --build-arg MODULE=user-service .

ARG MAVEN_IMAGE=maven:3.9-eclipse-temurin-25-alpine
ARG JRE_IMAGE=eclipse-temurin:25-jre-alpine

# =============================================================================
# Build stage
# =============================================================================
FROM ${MAVEN_IMAGE} AS build

ARG MODULE
ARG VERSION=1.0.0

WORKDIR /workspace
COPY . .

# The cache mount keeps the local Maven repository between builds without
# baking it into a layer, so a source-only change does not re-download the
# dependency tree.
#
# `-am` also builds the shared modules this one depends on. Tests are skipped
# here deliberately: they need Docker (Testcontainers) and belong in CI, not in
# an image build.
RUN --mount=type=cache,target=/root/.m2 \
    mvn -B -ntp -pl "${MODULE}" -am package -DskipTests

# Normalise the artefact path so the runtime stage does not need MODULE again.
RUN cp "/workspace/${MODULE}/target/${MODULE}-${VERSION}.jar" /workspace/application.jar

# =============================================================================
# Runtime stage
# =============================================================================
FROM ${JRE_IMAGE}

ARG MODULE
LABEL org.opencontainers.image.title="TRAMS ${MODULE}" \
      org.opencontainers.image.source="https://github.com/example/trams" \
      org.opencontainers.image.licenses="MIT"

# curl is needed for the Compose health check; there is no shell-accessible HTTP
# client in the base image.
RUN apk add --no-cache curl

# A dedicated unprivileged user. Running as root inside a container means a
# container escape starts with root on the host, and nothing here needs it.
RUN addgroup -S -g 10001 app && adduser -S -u 10001 -G app app

WORKDIR /app
COPY --from=build --chown=app:app /workspace/application.jar application.jar

USER 10001

EXPOSE 8080

# JVM tuning for a container:
#   MaxRAMPercentage      - the JVM defaults to a quarter of available memory,
#                           which wastes most of a small container's limit.
#   ExitOnOutOfMemoryError - fail fast so the orchestrator replaces the replica,
#                           rather than limping on in a degraded state.
#   Xss                   - virtual threads are enabled, so many stacks exist;
#                           a smaller stack keeps footprint predictable.
ENTRYPOINT ["java", \
    "-XX:MaxRAMPercentage=75.0", \
    "-XX:+ExitOnOutOfMemoryError", \
    "-XX:+UseStringDeduplication", \
    "-Xss512k", \
    "-Djava.security.egd=file:/dev/./urandom", \
    "-Dfile.encoding=UTF-8", \
    "-jar", "application.jar"]
