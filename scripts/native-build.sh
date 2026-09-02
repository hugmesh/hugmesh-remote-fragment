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
# Step 3 is the point. A native image that only COMPILES proves very little:
# missing reflection/resource metadata does not fail the build, it fails the
# first time the code path runs. Measured on two mutations of
# NativeHintsConfig, BOTH of which native-image reported as BUILD SUCCESS:
#   * without the FederationManifest hints the binary exits 1 with
#     "Record components not available for record class ...FederationManifest";
#   * without the BundleMetadata hint it exits 0, logs "1 failed" and writes
#     nothing -- so only the on-disk assertions catch it.
#
# There is NO skip path. Missing GraalVM, missing Docker, a failed compile or a
# failed smoke run all exit non-zero. An "it could not run so it passed" branch
# is exactly how a CI job goes inert.
#
# Usage:
#   scripts/native-build.sh [--no-smoke]
#
# Environment:
#   GRAALVM_HOME / JAVA_HOME  a JDK with bin/native-image (preferred)
#   GRAALVM_IMAGE             container image used when no local GraalVM exists
#                             (default ghcr.io/graalvm/graalvm-community:25)
#   NATIVE_BUILD_IN_DOCKER    1 = force the container path, 0 = forbid it
#   NATIVE_BUILD_M2           host Maven repo to bind into the container
#                             (optional cache; empty = throwaway repo)
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

GVM_HOME="$(find_graalvm)"
IN_DOCKER="${NATIVE_BUILD_IN_DOCKER:-auto}"
if [ "$IN_DOCKER" = "auto" ]; then
  if [ -n "$GVM_HOME" ]; then IN_DOCKER=0; else IN_DOCKER=1; fi
fi

if [ "$IN_DOCKER" = "1" ]; then
  command -v docker >/dev/null 2>&1 \
    || die "no local GraalVM (no bin/native-image under GRAALVM_HOME/JAVA_HOME, none on PATH) and no docker to fall back to.
       Install GraalVM for JDK 25 and set GRAALVM_HOME, or install Docker so this
       script can build in $GRAALVM_IMAGE."
  log "no local GraalVM -- building inside $GRAALVM_IMAGE"
  # --user keeps target/ owned by the invoking user: a root-owned build tree
  # breaks the GitLab shell executor's workspace cleanup on the next pipeline.
  # HOME=/tmp keeps the Maven repo OUT of the bind-mounted project dir -- this
  # repo has no .dockerignore, so a .m2 under the workspace would be swept into
  # the `docker build` context of the image jobs.
  M2_MOUNT=()
  if [ -n "${NATIVE_BUILD_M2:-}" ]; then
    M2_MOUNT=(-v "${NATIVE_BUILD_M2}:/tmp/.m2")
  fi
  SMOKE_ARG=()
  if [ "$RUN_SMOKE" = "0" ]; then SMOKE_ARG=(--no-smoke); fi
  exec docker run --rm \
    --user "$(id -u):$(id -g)" \
    -e HOME=/tmp \
    -e NATIVE_BUILD_IN_DOCKER=0 \
    -e "NATIVE_IMAGE_OPTIONS=${NATIVE_IMAGE_OPTIONS:-}" \
    -e "CI_JOB_TOKEN=${CI_JOB_TOKEN:-}" \
    -e "CI_API_V4_URL=${CI_API_V4_URL:-}" \
    -e "GITLAB_GROUP_ID=${GITLAB_GROUP_ID:-}" \
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
