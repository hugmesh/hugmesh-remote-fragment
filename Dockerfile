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
# SECOND REGISTRY PATH (GitHub Actions release.yml). When the BuildKit secret
# `packages_read_token` is mounted, the net.hugmesh libraries are resolved from GitHub
# Packages through .github/maven-ci-settings.xml (credential: UPSTREAM_PACKAGES_ACTOR build
# arg + the secret as UPSTREAM_PACKAGES_TOKEN) instead of the GitLab registry in settings.xml.
# With no such secret the legacy GitLab path below is unchanged, which is what GitLab CI and
# the local compose lane (m2-bootstrap.sh) rely on. A secret that is mounted but EMPTY is an
# error, not a silent fall-back to the GitLab path (it would die later as "/groups//").
# `set -x` is deliberately absent: it would echo the exported token into the build log.
ARG UPSTREAM_PACKAGES_ACTOR=github-actions
RUN --mount=type=secret,id=ci_job_token \
    --mount=type=secret,id=packages_read_token \
    sh -euc '\
      settings=settings.xml; \
      if [ -e /run/secrets/packages_read_token ]; then \
        if [ ! -s /run/secrets/packages_read_token ]; then \
          echo "FATAL: secret packages_read_token is mounted but empty (is PACKAGES_READ_TOKEN set?)" >&2; \
          exit 1; \
        fi; \
        UPSTREAM_PACKAGES_TOKEN="$(cat /run/secrets/packages_read_token)"; \
        export UPSTREAM_PACKAGES_TOKEN UPSTREAM_PACKAGES_ACTOR; \
        settings=.github/maven-ci-settings.xml; \
      elif [ -f /run/secrets/ci_job_token ]; then \
        CI_JOB_TOKEN="$(cat /run/secrets/ci_job_token)"; \
        export CI_JOB_TOKEN; \
      fi; \
      echo "maven settings: ${settings}"; \
      ./mvnw -B -s "${settings}" package -DskipTests'

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
