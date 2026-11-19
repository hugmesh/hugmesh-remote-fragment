#!/usr/bin/env bash
# GraalVM native build for hugmesh-remote-fragment -- the SINGLE definition of
# how the native binary is produced, shared by CI (build:native in
# .gitlab-ci.yml) and by developers (`make native`). One script so a CI failure
# is always reproducible locally with the same command, and so the two can
# never drift apart.
#
# What it does:
#   1. finds a GraalVM (GRAALVM_HOME / JAVA_HOME / PATH), or re-runs itself
#      inside the GraalVM container image when none is installed;
#   2. runs `./mvnw -Pnative -DskipTests native:compile`;
#   3. unless --no-smoke, RUNS the resulting binary against a stub federation +
#      Casdoor (scripts/NativeSmoke.java) in BOTH shapes the pod uses --
#      SYNC_MODE=once (bytes on disk) and SYNC_MODE=loop (readiness UP over
#      HTTP) -- and asserts what each one is supposed to produce.
#
# Step 3 is essential. A native image that only compiles proves very little:
# missing reflection/resource metadata does not fail native-image, it fails at
# first execution. Measured on two mutations of NativeHintsConfig:
#   * without the FederationManifest hints: binary exits 1 with
#     "Record components not available for record class ...FederationManifest"
#   * without the BundleMetadata hint: binary exits 0, logs "1 failed", writes
#     nothing — only the on-disk assertions catch it.
#
# No skip path: all failures (missing GraalVM/Docker, compile/smoke run failure)
# exit non-zero.
#
# Usage:
#   scripts/native-build.sh [--no-smoke]
#
# Environment:
#   GRAALVM_HOME / JAVA_HOME  a JDK with bin/native-image (preferred)
#   GRAALVM_IMAGE             container image used when no local GraalVM exists
#                             (default ghcr.io/graalvm/graalvm-community:25)
#   NATIVE_BUILD_IN_DOCKER    1 = force the container path, 0 = forbid it
#   NATIVE_BUILD_M2           host Maven repo to bind into the container. Defaults
#                             to ~/.m2 when there is no CI_JOB_TOKEN, because
#                             that is the only place the private hugmesh-commons
#                             exists on a dev box; unused on the CI path, which
#                             resolves cold from the GitLab registry
#   NATIVE_IMAGE_OPTIONS      passed through to native-image, e.g. "-J-Xmx6g"
set -euo pipefail

GRAALVM_IMAGE="${GRAALVM_IMAGE:-ghcr.io/graalvm/graalvm-community:25}"
REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
BINARY="target/remote-fragment"

RUN_SMOKE=1
for arg in "$@"; do
  case "$arg" in
    --no-smoke) RUN_SMOKE=0 ;;
    *) echo "native-build: unknown argument: $arg" >&2; exit 2 ;;
  esac
done

log() { echo "[native] $*"; }
die() { echo "[native] FAIL: $*" >&2; exit 1; }

# Locate a JDK that actually has native-image. Echoes the home directory, or
# nothing when there is none.
find_graalvm() {
  local candidate
  for candidate in "${GRAALVM_HOME:-}" "${JAVA_HOME:-}"; do
    if [ -n "$candidate" ] && [ -x "$candidate/bin/native-image" ]; then
      echo "$candidate"
      return 0
    fi
  done
  local on_path
  on_path="$(command -v native-image 2>/dev/null || true)"
  if [ -n "$on_path" ]; then
    dirname "$(dirname "$on_path")"
    return 0
  fi
  return 0
}

cd "$REPO_ROOT"

# ---------------------------------------------------------------------------
# Where net.hugmesh:hugmesh-commons comes from -- the ONE dependency in this
# pom that is NOT on Maven Central. There are exactly two ways it can resolve,
# and if neither is available this script has to say so HERE. Without this
# check the run dies ~30 s later, inside Maven, as
# "Could not find artifact net.hugmesh:hugmesh-commons:jar:2.1.0 in central" --
# an error that names Central, i.e. the one place the artifact was never going
# to be, and says nothing about the credential or the repo that were missing.
#
#   CI  -- CI_JOB_TOKEN is set, so settings.xml points Maven at the org's
#          GitLab group Maven registry (see MVN_SETTINGS below). Nothing is
#          mounted; every pipeline resolves cold. CI_API_V4_URL and
#          GITLAB_GROUP_ID must BOTH be non-empty: settings.xml interpolates
#          them into the URL, and an empty group id yields
#          ".../groups//-/packages/maven", which 404s and is then reported as
#          a missing ARTIFACT rather than a missing VARIABLE. CI_API_V4_URL
#          must also be https (or an http loopback) -- Maven 3.8+ blocks plain
#          http repositories and blames a "mirror" for it.
#
#   dev -- no CI_JOB_TOKEN. The artifact is in the developer's ~/.m2 (that is
#          how a plain `./mvnw test` resolves it here), so the host repo is
#          bind-mounted into the container. It is NOT reachable otherwise: the
#          container deliberately runs with HOME=/tmp, so without the mount
#          Maven starts from an empty repo and falls through to Central.
#          Measured: `make native` on a box with a perfectly good warm ~/.m2
#          failed exactly that way before this mount existed.
M2_REPO_HOME="${NATIVE_BUILD_M2:-$HOME/.m2}"

preflight_commons() {
  if [ -n "${CI_JOB_TOKEN:-}" ]; then
    [ -n "${CI_API_V4_URL:-}" ] \
      || die "CI_JOB_TOKEN is set but CI_API_V4_URL is empty. settings.xml builds the
       registry URL out of both, and an empty one resolves to a 404 that Maven
       then reports as a missing net.hugmesh:hugmesh-commons artifact."
    # ...and non-empty is not enough: it has to be https. Maven 3.8+ ships a
    # `maven-default-http-blocker` mirror that blocks EVERY plain-http repository
    # except localhost, so an http CI_API_V4_URL cannot resolve anything -- and it
    # fails in exactly the misleading shape this preflight exists to prevent.
    # Measured here, before this check existed, with an http URL:
    #   "Could not transfer artifact net.hugmesh:hugmesh-commons:pom:2.1.0 from/to
    #    maven-default-http-blocker (http://0.0.0.0/): Blocked mirror for
    #    repositories: [gitlab-maven (http://...)]"
    # -- an error naming a MIRROR nobody configured, said in the vocabulary of
    # Maven's internals rather than of the CI variable that is actually wrong.
    # This job has never executed on a real runner (see the README), so if the
    # in-cluster GitLab publishes an http CI_API_V4_URL, that is what its first
    # ever run would print. Cheap to say plainly instead.
    case "${CI_API_V4_URL:-}" in
      https://*) ;;
      # Maven's blocker exempts localhost, so an http loopback URL genuinely works.
      http://localhost|http://localhost/*|http://localhost:*) ;;
      http://127.0.0.1|http://127.0.0.1/*|http://127.0.0.1:*) ;;
      'http://[::1]'|'http://[::1]/'*|'http://[::1]:'*) ;;
      *) die "CI_API_V4_URL is \"${CI_API_V4_URL:-}\", which is not https (and not an
       http loopback). Maven 3.8+ blocks every plain-http repository that is not
       localhost via maven-default-http-blocker, so hugmesh-commons would fail to
       resolve with \"Blocked mirror for repositories: [gitlab-maven (...)]\" -- an
       error that names a mirror nobody configured. Point CI_API_V4_URL at the
       https GitLab API endpoint." ;;
    esac
    [ -n "${GITLAB_GROUP_ID:-}" ] \
      || die "CI_JOB_TOKEN is set but GITLAB_GROUP_ID is empty, so settings.xml would
       ask for \"\${CI_API_V4_URL}/groups//-/packages/maven\". Set GITLAB_GROUP_ID
       to the org group id as a CI/CD variable."
    log "CI_JOB_TOKEN present -- hugmesh-commons will resolve from the GitLab registry"
    return 0
  fi
  [ -d "${M2_REPO_HOME}/repository/net/hugmesh/hugmesh-commons" ] \
    || die "net.hugmesh:hugmesh-commons is private -- it is NOT on Maven Central -- and
       there is no way to fetch it here: no CI_JOB_TOKEN (the CI path), and no
       copy under ${M2_REPO_HOME}/repository/net/hugmesh/hugmesh-commons (the
       developer path). Either run \`./mvnw test\` once so the artifact is in your
       ~/.m2, or point NATIVE_BUILD_M2 at a Maven repo that already has it, or
       export CI_JOB_TOKEN/CI_API_V4_URL/GITLAB_GROUP_ID for the registry."
  log "resolving hugmesh-commons from the local Maven repo at ${M2_REPO_HOME}"
}

preflight_commons

GVM_HOME="$(find_graalvm)"
IN_DOCKER="${NATIVE_BUILD_IN_DOCKER:-auto}"
if [ "$IN_DOCKER" = "auto" ]; then
  if [ -n "$GVM_HOME" ]; then IN_DOCKER=0; else IN_DOCKER=1; fi
fi

if [ "$IN_DOCKER" = "1" ]; then
  command -v docker >/dev/null 2>&1 \
    || die "the container toolchain was selected (NATIVE_BUILD_IN_DOCKER=${NATIVE_BUILD_IN_DOCKER:-auto};
       'auto' means no bin/native-image was found under GRAALVM_HOME/JAVA_HOME or on
       PATH) but docker is not installed. Install Docker so this script can build in
       $GRAALVM_IMAGE, or install GraalVM for JDK 25, set GRAALVM_HOME and re-run with
       NATIVE_BUILD_IN_DOCKER=0 -- note that then compiles with YOUR toolchain, not
       the pinned one CI uses."
  log "no local GraalVM -- building inside $GRAALVM_IMAGE"
  # --user keeps target/ owned by the invoking user: a root-owned build tree
  # breaks the GitLab shell executor's workspace cleanup on the next pipeline.
  # HOME=/tmp keeps the Maven repo OUT of the bind-mounted project dir: the
  # shell executor's workspace is shared by test, build:native and build:sync,
  # and a ~500 MB .m2 in it is left for the next pipeline to trip over. The root
  # .dockerignore does also exclude `.m2/` from the image jobs' `docker build`
  # context, so that is belt-and-braces -- what is wanted here is workspace
  # hygiene, not just a clean build context.
  # The container's HOME is /tmp, so /tmp/.m2 is the Maven repo it will use.
  # With a CI_JOB_TOKEN we mount nothing and let the pipeline resolve cold from
  # the GitLab registry; without one, the host repo is the only place
  # hugmesh-commons exists, so it is mounted there (preflight_commons above has
  # already established that it is present).
  M2_MOUNT=()
  if [ -n "${NATIVE_BUILD_M2:-}" ]; then
    M2_MOUNT=(-v "${NATIVE_BUILD_M2}:/tmp/.m2")
  elif [ -z "${CI_JOB_TOKEN:-}" ]; then
    M2_MOUNT=(-v "${M2_REPO_HOME}:/tmp/.m2")
    log "bind-mounting ${M2_REPO_HOME} -> /tmp/.m2 (private hugmesh-commons lives there)"
  fi
  SMOKE_ARG=()
  if [ "$RUN_SMOKE" = "0" ]; then SMOKE_ARG=(--no-smoke); fi
  # CI_JOB_TOKEN is passed by NAME, not as `-e CI_JOB_TOKEN=<value>`. The value
  # form puts the token in the docker CLI's argv, i.e. in the WORLD-READABLE
  # host process table for the entire life of the container -- and this
  # container is a native compile, so that is minutes, on a runner tagged
  # `privileged`. Measured on this box: with `-e VAR=secret`, `ps -eo args`
  # prints `docker run --rm -e TESTSECRET=leaky-token-abc123 ...` for the whole
  # run; with `-e VAR` the token appears nowhere in argv and still arrives
  # intact inside the container. GitLab's variable masking only covers job LOG
  # output, so it does not help here.
  #
  # SCOPE -- what this does NOT close, also measured here, so nobody reads more
  # into it than it earns. A sibling job on the SAME runner can still get the
  # value two ways while the compile runs: `docker inspect <cid> --format
  # '{{json .Config.Env}}'` prints it verbatim, and every job in this pipeline
  # has docker access (that is how test, build:native and build:sync all work);
  # and /proc/<docker-cli-pid>/environ is mode 0400 owned by the invoking user,
  # which on a GitLab *shell* executor is the one `gitlab-runner` uid shared by
  # all jobs. Closing those needs a different mechanism -- a file-based secret,
  # or a rootless/isolated executor -- not a different `-e` form. What the
  # pass-through form does buy is real: it takes the token out of a channel that
  # every local user, every `ps` scraper and every process-table monitor can
  # read, and costs nothing.
  #
  # Same pass-through form the `test` job in .gitlab-ci.yml already uses, and the
  # same reasoning that makes build:sync hand the token to `docker build` as a
  # BuildKit secret rather than a build ARG. Behaviour is unchanged for an unset
  # variable: docker simply omits it, and every read in this script is
  # `${VAR:-}`, so unset and empty are already treated alike.
  exec docker run --rm \
    --user "$(id -u):$(id -g)" \
    -e HOME=/tmp \
    -e NATIVE_BUILD_IN_DOCKER=0 \
    -e "NATIVE_IMAGE_OPTIONS=${NATIVE_IMAGE_OPTIONS:-}" \
    -e CI_JOB_TOKEN \
    -e CI_API_V4_URL \
    -e GITLAB_GROUP_ID \
    "${M2_MOUNT[@]}" \
    -v "${REPO_ROOT}:/workspace" \
    -w /workspace \
    --entrypoint /bin/bash \
    "$GRAALVM_IMAGE" scripts/native-build.sh "${SMOKE_ARG[@]}"
fi

[ -n "$GVM_HOME" ] || die "GRAALVM_HOME/JAVA_HOME has no bin/native-image and none is on PATH."
export JAVA_HOME="$GVM_HOME"
export GRAALVM_HOME="$GVM_HOME"
export PATH="$GVM_HOME/bin:$PATH"
log "using GraalVM at $GVM_HOME"
"$GVM_HOME/bin/native-image" --version

# settings.xml points Maven at the org's GitLab Maven registry for the private
# net.hugmesh:hugmesh-commons. Only useful where CI_JOB_TOKEN exists; on a dev
# box the artifact is already in ~/.m2 and the unresolved ${env.*} placeholders
# in that file would only get in the way.
MVN_SETTINGS=()
if [ -n "${CI_JOB_TOKEN:-}" ]; then
  log "CI_JOB_TOKEN present -- resolving dependencies through settings.xml"
  MVN_SETTINGS=(-s settings.xml)
fi

log "compiling: ./mvnw -Pnative -DskipTests native:compile"
./mvnw -B "${MVN_SETTINGS[@]}" -Pnative -DskipTests native:compile

[ -x "$BINARY" ] || die "native:compile reported success but $BINARY is missing."
log "built $BINARY ($(du -h "$BINARY" | cut -f1))"

if [ "$RUN_SMOKE" = "0" ]; then
  log "--no-smoke: skipping the runtime check (compile-only)."
  exit 0
fi

log "running the boot smoke test against the native binary"
java scripts/NativeSmoke.java "$BINARY"
log "OK -- native binary compiled and verified at runtime."
