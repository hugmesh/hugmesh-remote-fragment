# hugmesh-remote-fragment SYNC SIDECAR — the first-party reactive Java service
# that mints a Casdoor service token and pulls the enabled fragment bundles from
# the federation API into the shared nginx root.
#
# Mirrors hugmesh-admin-bff-api / hugmesh-federation-api: a two-stage JVM build
# (jdk build -> jre run). The GraalVM native binary is produced by the pom's
# `native` profile (`./mvnw -Pnative native:compile`); this JRE-jar image is the
# default the CI builds, matching the rest of the fleet.
FROM eclipse-temurin:25-jdk AS build
WORKDIR /work
COPY . .
RUN ./mvnw -B package -DskipTests || mvn -B package -DskipTests

FROM eclipse-temurin:25-jre
# curl is required by this image's docker-compose HEALTHCHECK (actuator/health/readiness);
# eclipse-temurin's jre base does not ship it, which silently breaks every
# `depends_on: condition: service_healthy` gate on a fresh compose boot.
RUN apt-get update && apt-get install -y --no-install-recommends curl && rm -rf /var/lib/apt/lists/*
WORKDIR /app
COPY --from=build /work/target/*.jar app.jar
# Actuator listens on 8081 in-pod (nginx owns 8080); SERVER_PORT overrides.
EXPOSE 8081
ENTRYPOINT ["java","-jar","/app/app.jar"]
