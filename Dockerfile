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

# Which Maven settings file resolves net.hugmesh:hugmesh-commons (and hugmesh-parent).
#   settings.xml         (default) the GitLab group registry. GitLab CI passes
#                        CI_API_V4_URL + GITLAB_GROUP_ID as build args and the
#                        ci_job_token build secret. The GitLab pipeline does not set
#                        MAVEN_SETTINGS, so it is unchanged.
#   settings-github.xml  GitHub Packages. .github/workflows/release.yml passes
#                        MAVEN_SETTINGS=settings-github.xml and the packages_token /
#                        packages_actor build secrets. Selected by build arg, not by
#                        which secret happens to be present: GitLab mounts ci_job_token too.
ARG MAVEN_SETTINGS=settings.xml

# GitLab registry coordinates the settings.xml interpolates ($CI_API_V4_URL is the
# instance API base; GITLAB_GROUP_ID selects the org group whose package registry
# holds hugmesh-parent/hugmesh-commons). Same pair the BFF/federation siblings take.
# Unused (empty) on the GitHub Packages path.
ARG CI_API_V4_URL
ARG GITLAB_GROUP_ID
ENV CI_API_V4_URL=${CI_API_V4_URL} \
    GITLAB_GROUP_ID=${GITLAB_GROUP_ID}

COPY . .

# `-s "$MAVEN_SETTINGS"` points Maven at the registry that holds the private
# net.hugmesh:hugmesh-commons artifact. Without it Maven resolves from central only
# and the build dies at the first goal with "Could not find artifact
# net.hugmesh:hugmesh-commons" — with no mention of the registry it should have used.
#
# The former `|| mvn -B package` fallback is deliberately gone. mvnw is committed, so
# the fallback could only ever fire when mvnw itself had ALREADY failed — and since no
# `mvn` exists in this image, it replaced the real Maven diagnostic with a bare
# "exit code: 127", which is how a missing-artifact error spent a build cycle looking
# like a broken toolchain.
#
# Tokens are read from secret files, exported only for this one shell, and xtrace is
# switched on AFTER they are read so the expanded values never reach the build log.
# Both mounts are optional: a build without them still runs and fails at resolution with
# Maven's own message.
RUN --mount=type=secret,id=ci_job_token \
    --mount=type=secret,id=packages_token \
    --mount=type=secret,id=packages_actor \
    sh -euc '\
      if [ -f /run/secrets/ci_job_token ]; then \
        export CI_JOB_TOKEN="$(cat /run/secrets/ci_job_token)"; \
      fi; \
      if [ -f /run/secrets/packages_token ]; then \
        export UPSTREAM_PACKAGES_TOKEN="$(cat /run/secrets/packages_token)"; \
      fi; \
      if [ -f /run/secrets/packages_actor ]; then \
        export UPSTREAM_PACKAGES_ACTOR="$(cat /run/secrets/packages_actor)"; \
      fi; \
      set -x; \
      ./mvnw -B -s "$MAVEN_SETTINGS" package -DskipTests'

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
