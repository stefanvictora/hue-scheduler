# -------- Build --------
FROM --platform=$BUILDPLATFORM maven:3.9.11-eclipse-temurin-24 AS build
WORKDIR /build
COPY pom.xml .
COPY src ./src
RUN --mount=type=cache,target=/root/.m2,sharing=locked mvn -B -ntp package

# -------- Runtime --------
FROM eclipse-temurin:24-jre-alpine AS runtime

# Create non-root user
RUN addgroup -S app && adduser -S -G app app
WORKDIR /app

COPY --from=build --chown=app:app /build/target/hue-scheduler.jar /app/hue-scheduler.jar

RUN printf '%s\n' \
  '#!/bin/sh' \
  'set -e' \
  'TZ_PROP="-Duser.timezone=${TZ:-UTC}"' \
  'exec java $TZ_PROP $JAVA_TOOL_OPTIONS -XX:+ExitOnOutOfMemoryError -jar /app/hue-scheduler.jar' \
  > /app/entrypoint.sh \
  && chmod +x /app/entrypoint.sh

ENV JAVA_TOOL_OPTIONS=""

LABEL org.opencontainers.image.title="Hue Scheduler" \
      org.opencontainers.image.source="https://github.com/stefanvictora/hue-scheduler"

USER app
ENTRYPOINT ["/app/entrypoint.sh"]
