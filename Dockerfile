# -------- Build --------
FROM --platform=$BUILDPLATFORM maven:3.9.11-eclipse-temurin-25 AS build
WORKDIR /build
COPY pom.xml .
RUN --mount=type=cache,target=/root/.m2,sharing=locked mvn -B -ntp dependency:go-offline
COPY src ./src
RUN --mount=type=cache,target=/root/.m2,sharing=locked mvn -B -ntp package

# -------- Runtime --------
FROM eclipse-temurin:25-jre-alpine AS runtime

# Create non-root user
RUN addgroup -S -g 10001 app && adduser -S -u 10001 -G app app
WORKDIR /app

COPY --from=build --chown=app:app /build/target/hue-scheduler.jar /app/hue-scheduler.jar

RUN printf '%s\n' \
  '#!/bin/sh' \
  'set -e' \
  'TZ_PROP="-Duser.timezone=${TZ:-UTC}"' \
  'exec java $TZ_PROP -XX:+ExitOnOutOfMemoryError -jar /app/hue-scheduler.jar' \
  > /app/entrypoint.sh \
  && chmod +x /app/entrypoint.sh

LABEL org.opencontainers.image.title="Hue Scheduler" \
      org.opencontainers.image.source="https://github.com/stefanvictora/hue-scheduler"

USER 10001:10001
ENTRYPOINT ["/app/entrypoint.sh"]
