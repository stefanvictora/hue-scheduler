# Stage 1: Cache dependencies
FROM maven:3.9.11-eclipse-temurin-24 AS dependencies
WORKDIR /build/
COPY pom.xml .
# Download all required dependencies first (this will only be re-run if the pom file changes)
RUN mvn --batch-mode dependency:go-offline dependency:resolve-plugins

# Stage 2: Build the Java application
FROM maven:3.9.11-eclipse-temurin-24 AS build
COPY --from=dependencies /root/.m2 /root/.m2
WORKDIR /build/
COPY pom.xml .
COPY src ./src
RUN mvn --batch-mode -ntp --fail-fast package

# Stage 3: Create the runtime image
FROM eclipse-temurin:24-jre-alpine AS runtime
WORKDIR /usr/src/hue-scheduler
COPY --from=build /build/target/hue-scheduler.jar .
ENTRYPOINT ["java", "-jar", "hue-scheduler.jar"]
