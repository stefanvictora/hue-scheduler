# Stage 1: Build the Java application
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /usr/src/hue-scheduler
COPY pom.xml .
# Download all required dependencies first (this will only be re-run if the pom file changes)
RUN mvn dependency:go-offline
COPY src ./src
RUN mvn clean package

# Stage 2: Create the runtime image
FROM eclipse-temurin:21-alpine
WORKDIR /usr/src/hue-scheduler
COPY --from=build /usr/src/hue-scheduler/target/hue-scheduler.jar .
ENTRYPOINT ["java", "-jar", "hue-scheduler.jar"]
