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

# GitLab registry coordinates the settings.xml interpolates ($CI_API_V4_URL is the
# instance API base; GITLAB_GROUP_ID selects the org group whose package registry
# holds hugmesh-parent/hugmesh-commons). Same pair the BFF/federation siblings take.
ARG CI_API_V4_URL
ARG GITLAB_GROUP_ID
ENV CI_API_V4_URL=${CI_API_V4_URL} \
    GITLAB_GROUP_ID=${GITLAB_GROUP_ID}

COPY . .

# `-s settings.xml` points Maven at the gitlab-maven registry for the private
# net.hugmesh:hugmesh-commons artifact. Without it Maven resolves from central only
# and the build dies at the first goal with "Could not find artifact
# net.hugmesh:hugmesh-commons" — with no mention of the registry it should have used.
#
# The former `|| mvn -B package` fallback is deliberately gone. mvnw is committed, so
# the fallback could only ever fire when mvnw itself had ALREADY failed — and since no
# `mvn` exists in this image, it replaced the real Maven diagnostic with a bare
# "exit code: 127", which is how a missing-artifact error spent a build cycle looking
# like a broken toolchain.
RUN --mount=type=secret,id=ci_job_token \
    sh -euxc '\
      if [ -f /run/secrets/ci_job_token ]; then \
        export CI_JOB_TOKEN="$(cat /run/secrets/ci_job_token)"; \
      fi; \
      ./mvnw -B -s settings.xml package -DskipTests'

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
