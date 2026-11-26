FROM --platform=$BUILDPLATFORM maven:3.9.11-eclipse-temurin-17 AS build
WORKDIR /build
COPY backend/pom.xml ./
RUN --mount=type=cache,target=/root/.m2 mvn -B -ntp dependency:go-offline
COPY backend/src ./src
RUN --mount=type=cache,target=/root/.m2 mvn -B -ntp package \
    && cp target/orderflow-*.jar /orderflow.jar

FROM eclipse-temurin:17-jre-jammy
WORKDIR /app
COPY --from=build /orderflow.jar ./orderflow.jar
# Only the audited V1.0 experiment results read by the existing backend.
COPY evidence/indexes.txt evidence/concurrency.txt evidence/binlog.txt evidence/replication.txt evidence/api-acceptance.txt ./evidence/
USER 10001:10001
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/orderflow.jar"]
