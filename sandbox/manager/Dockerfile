FROM maven:3.9.9-eclipse-temurin-21 AS build
WORKDIR /build
COPY pom.xml .
COPY src src
RUN --mount=type=cache,target=/root/.m2 mvn -B -q -DskipTests package
FROM docker:28-cli AS docker-cli
FROM maven:3.9.9-eclipse-temurin-21
COPY --from=docker-cli /usr/local/bin/docker /usr/local/bin/docker
COPY --from=build /build/target/sandbox-manager-0.0.1-SNAPSHOT.jar /app/manager.jar
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=65", "-jar", "/app/manager.jar"]
