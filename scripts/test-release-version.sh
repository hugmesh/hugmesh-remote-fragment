#!/usr/bin/env bash
# Tests for scripts/release-version.sh (the release.yml version guard). No network: tag cases
# run against a throwaway git repository built in a temp dir. The overwrite-refusal steps of
# release.yml are executed by scripts/test-release-workflow.sh and test-release-workflow.py.
# Run: bash scripts/test-release-version.sh
set -euo pipefail

SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
GUARD="${SCRIPT_DIR}/release-version.sh"
REAL_POM="${SCRIPT_DIR}/../pom.xml"
IMG=ghcr.io/hugmesh/hugmesh-remote-fragment
NGINX_IMG=ghcr.io/hugmesh/hugmesh-remote-fragment/nginx
SHA=0123456789abcdef0123456789abcdef01234567
WORK="$(mktemp -d)"
trap 'rm -rf "${WORK}"' EXIT
FAILURES=0

pom() {
  # $1 = project version. The parent carries a DIFFERENT version on purpose: the guard must
  # read <project><version>, never the first <version> in the file.
  cat > "${WORK}/pom.xml" <<EOP
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0">
  <modelVersion>4.0.0</modelVersion>
  <parent>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-parent</artifactId>
    <version>9.9.9</version>
  </parent>
  <artifactId>remote-fragment</artifactId>
  <version>$1</version>
</project>
EOP
}

# ── A throwaway repository with the branch topology the guard reads ─────────────────────
#
#   B0 ── M1 ── M2 ── M3                      origin/main
#          \
#           R1 ── R2                          origin/release/0.2   (branched at M1)
#   B0 ── O1                                  origin/release/0.1   (an older line)
#
# Tags already "published": v0.1.0 (on O1), v0.2.0 (on R1). Each test that needs a newer
# line adds and removes its own tags.
REPO="${WORK}/repo"
g() { git -C "${REPO}" -c user.name=t -c user.email=t@example.invalid -c commit.gpgsign=false -c tag.gpgSign=false "$@"; }
git init -q "${REPO}"
commit() { g commit -q --allow-empty -m "$1"; g rev-parse HEAD; }
B0="$(commit base)"
M1="$(commit m1)"
M2="$(commit m2)"
M3="$(commit m3)"
g update-ref refs/remotes/origin/main "${M3}"
g checkout -q -b r02 "${M1}"
R1="$(commit r1)"
R2="$(commit r2)"
g update-ref refs/remotes/origin/release/0.2 "${R2}"
g checkout -q -b r01 "${B0}"
O1="$(commit o1)"
g update-ref refs/remotes/origin/release/0.1 "${O1}"
g tag v0.1.0 "${O1}"
g tag v0.2.0 "${R1}"

run() {
  # $1 = ref, $2 = commit (default ${SHA}), $3 = image (default the sync image); runs the guard
  # against ${WORK}/pom.xml and the throwaway repository; sets OUT and RC.
  set +e
  OUT="$(RELEASE_POM="${WORK}/pom.xml" RELEASE_GIT_DIR="${REPO}" IMAGE="${3:-${IMG}}" GITHUB_REF="$1" GITHUB_SHA="${2:-${SHA}}" GITHUB_OUTPUT="" bash "${GUARD}" 2>&1)"
  RC=$?
  set -e
}

check() {
  # $1 = description, $2 = condition result (0 = pass)
  if [ "$2" -eq 0 ]; then
    printf 'ok   %s\n' "$1"
  else
    printf 'FAIL %s\n---- guard output:\n%s\n----\n' "$1" "${OUT}" >&2
    FAILURES=$((FAILURES + 1))
  fi
}

has() { case "${OUT}" in *"$1"*) return 0 ;; *) return 1 ;; esac; }
lacks() { if has "$1"; then return 1; else return 0; fi; }
# tags_are <exact comma-separated list>: the tags= line is exactly this, nothing more.
tags_are() { case $'\n'"${OUT}"$'\n' in *$'\n'"tags=$1"$'\n'*) return 0 ;; *) return 1 ;; esac; }

# 1. push to main: :main + :sha only, never a version, :beta or :latest.
pom 0.2.0-beta.1; run refs/heads/main
r=0; [ "${RC}" -eq 0 ] && has 'kind=main' && has "tags=${IMG}:main,${IMG}:sha-0123456789ab" \
  && lacks ':latest' && lacks ':beta' && lacks ":0.2.0-beta.1" && has 'channel=none' || r=1
check "main publishes :main and :sha-<12> only" "${r}"

# 2. matching beta tag on a main commit: :<version>, :sha, :beta; never :latest or :X.Y.
pom 0.3.0-beta.1; run refs/tags/v0.3.0-beta.1 "${M3}"
r=0; [ "${RC}" -eq 0 ] && has 'kind=release' && has 'version=0.3.0-beta.1' && has 'channel=beta' && has 'latest=false' \
  && tags_are "${IMG}:0.3.0-beta.1,${IMG}:sha-${M3:0:12},${IMG}:beta" && lacks ':latest' && lacks "${IMG}:0.3," || r=1
check "beta tag publishes :<version>, :sha-<12> and :beta, no :latest" "${r}"

# 2b. a beta tag on an older main commit (behind the tip) is still on main.
pom 0.2.0-beta.3; run refs/tags/v0.2.0-beta.3 "${M2}"
r=0; [ "${RC}" -eq 0 ] && has 'channel=beta' || r=1
check "beta tag on an earlier main commit is accepted" "${r}"

# 3. tag that does not match the pom version is refused.
pom 0.2.0-beta.1; run refs/tags/v0.2.0-beta.2 "${M3}"
r=0; [ "${RC}" -ne 0 ] && has 'does not match' && lacks 'kind=' || r=1
check "tag v0.2.0-beta.2 against pom 0.2.0-beta.1 is refused" "${r}"

# 4. a SNAPSHOT is never published, not even from main.
pom 0.2.0-SNAPSHOT; run refs/heads/main
r=0; [ "${RC}" -ne 0 ] && has 'SNAPSHOT' && lacks 'kind=' || r=1
check "SNAPSHOT version is refused on main" "${r}"

# 5. other branches, pull-request refs, non-v tags and malformed release branches are refused.
pom 0.2.0-beta.1
for ref in refs/heads/feature/x refs/pull/7/merge refs/tags/studio-v0.2.0-beta.1 refs/tags/0.2.0-beta.1 \
           refs/heads/release/x refs/heads/release/0.2.1 refs/heads/release/0 refs/heads/release/0.2/x; do
  run "${ref}"
  r=0; [ "${RC}" -ne 0 ] && lacks 'kind=' || r=1
  check "ref ${ref} is refused" "${r}"
done

# 6. '+build' metadata cannot become an OCI tag, with or without a prerelease part.
for v in 0.2.0-beta.1+build.7 0.2.0+build.7; do
  pom "${v}"; run refs/heads/main
  r=0; [ "${RC}" -ne 0 ] && lacks 'kind=' || r=1
  check "version ${v} with +build metadata is refused" "${r}"
done

# 7. a final (stable) release on the newest line moves :latest; any other prerelease (rc)
#    names no channel and is refused.
g update-ref refs/remotes/origin/release/1.0 "${R2}"
pom 1.0.0; run refs/tags/v1.0.0 "${R2}"
r=0; [ "${RC}" -eq 0 ] && has "${IMG}:latest" && lacks ':beta' || r=1
check "final 1.0.0 tag adds :latest" "${r}"
pom 1.0.0-rc.1; run refs/tags/v1.0.0-rc.1 "${M3}"
r=0; [ "${RC}" -ne 0 ] && has 'names no release channel' && lacks 'kind=' && lacks ':latest' && lacks ':beta' || r=1
check "rc tag is refused (no channel), moves neither :beta nor :latest" "${r}"
g update-ref -d refs/remotes/origin/release/1.0

# 7b. the nginx image (the second image, same version) gets its own tag list, per channel.
pom 0.3.0-beta.1; run refs/tags/v0.3.0-beta.1 "${M3}" "${NGINX_IMG}"
r=0; [ "${RC}" -eq 0 ] && has 'kind=release' && has 'version=0.3.0-beta.1' && has 'channel=beta' && has 'latest=false' \
  && tags_are "${NGINX_IMG}:0.3.0-beta.1,${NGINX_IMG}:sha-${M3:0:12},${NGINX_IMG}:beta" && lacks ':latest' || r=1
check "nginx beta tag publishes nginx :<version>, :sha-<12> and :beta, no :latest" "${r}"
pom 0.2.1; run refs/tags/v0.2.1 "${R2}" "${NGINX_IMG}"
r=0; [ "${RC}" -eq 0 ] && has 'channel=stable' && has 'latest=true' \
  && tags_are "${NGINX_IMG}:0.2.1,${NGINX_IMG}:sha-${R2:0:12},${NGINX_IMG}:0.2,${NGINX_IMG}:latest" || r=1
check "nginx stable v0.2.1 on release/0.2 publishes nginx :0.2.1, :sha, :0.2 and :latest" "${r}"
pom 0.1.1; run refs/tags/v0.1.1 "${O1}" "${NGINX_IMG}"
r=0; [ "${RC}" -eq 0 ] && has 'latest=false' \
  && tags_are "${NGINX_IMG}:0.1.1,${NGINX_IMG}:sha-${O1:0:12},${NGINX_IMG}:0.1" && lacks ':latest' || r=1
check "nginx older-line stable v0.1.1 publishes nginx :0.1.1, :sha and :0.1, not :latest" "${r}"
pom 0.1.0-beta.1; run refs/heads/main "${SHA}" "${NGINX_IMG}"
r=0; [ "${RC}" -eq 0 ] && tags_are "${NGINX_IMG}:main,${NGINX_IMG}:sha-0123456789ab" && lacks ':latest' || r=1
check "nginx main publishes nginx :main and :sha-<12> only" "${r}"

# 8. the committed pom.xml passes the guard on main (beta on main, or X.Y.Z on release/X.Y).
set +e
OUT="$(RELEASE_POM="${REAL_POM}" RELEASE_GIT_DIR="${REPO}" IMAGE="${IMG}" GITHUB_REF=refs/heads/main GITHUB_SHA="${SHA}" GITHUB_OUTPUT="" bash "${GUARD}" 2>&1)"
RC=$?
set -e
r=0; [ "${RC}" -eq 0 ] && has 'version=' || r=1
check "committed pom.xml version passes the guard" "${r}"

# 8b. a plain X.Y.Z pom, as a stable bump leaves it, passes on its release/X.Y branch and on main.
pom 0.2.1; run refs/heads/release/0.2 "${R2}"
r=0; [ "${RC}" -eq 0 ] && has 'version=0.2.1' || r=1
check "plain X.Y.Z pom passes the guard on release/X.Y" "${r}"
pom 0.2.1; run refs/heads/main "${M3}"
r=0; [ "${RC}" -eq 0 ] && has 'version=0.2.1' && lacks ':latest' && lacks "${IMG}:0.2" || r=1
check "plain X.Y.Z pom on main publishes :main and :sha-<12> only" "${r}"

# 9. GITHUB_OUTPUT receives the same six keys.
pom 0.3.0-beta.1
: > "${WORK}/out"
RELEASE_POM="${WORK}/pom.xml" RELEASE_GIT_DIR="${REPO}" IMAGE="${IMG}" GITHUB_REF=refs/tags/v0.3.0-beta.1 GITHUB_SHA="${M3}" GITHUB_OUTPUT="${WORK}/out" bash "${GUARD}" >/dev/null
OUT="$(cat "${WORK}/out")"
r=0; has 'kind=release' && has 'version=0.3.0-beta.1' && has "sha12=${M3:0:12}" && has "tags=${IMG}:0.3.0-beta.1" \
  && has 'channel=beta' && has 'latest=false' || r=1
check "GITHUB_OUTPUT gets kind, version, sha12, tags, channel and latest" "${r}"

# ── Release channels ────────────────────────────────────────────────────────────────────

# 10. stable on release/X.Y, newest line: :X.Y.Z, :sha, :X.Y and :latest (and never :beta).
pom 0.2.1; run refs/tags/v0.2.1 "${R2}"
r=0; [ "${RC}" -eq 0 ] && has 'kind=release' && has 'channel=stable' && has 'latest=true' \
  && tags_are "${IMG}:0.2.1,${IMG}:sha-${R2:0:12},${IMG}:0.2,${IMG}:latest" || r=1
check "stable v0.2.1 on release/0.2 (newest line) pushes :0.2.1, :sha, :0.2 and :latest" "${r}"

# 10b. the first stable of a line, with no earlier stable tag at all, is the newest line.
g tag -d v0.1.0 >/dev/null; g tag -d v0.2.0 >/dev/null
pom 0.2.0; run refs/tags/v0.2.0 "${R1}"
r=0; [ "${RC}" -eq 0 ] && has 'latest=true' && tags_are "${IMG}:0.2.0,${IMG}:sha-${R1:0:12},${IMG}:0.2,${IMG}:latest" || r=1
check "first stable v0.2.0 with no prior stable tags moves :latest" "${r}"
g tag v0.1.0 "${O1}"; g tag v0.2.0 "${R1}"

# 10c. the tag being released is ignored when it already exists locally (the tag-push case).
g tag v0.2.1 "${R2}"
pom 0.2.1; run refs/tags/v0.2.1 "${R2}"
r=0; [ "${RC}" -eq 0 ] && has 'latest=true' || r=1
check "the tag being released does not count against itself" "${r}"
g tag -d v0.2.1 >/dev/null

# 11. stable patch on an OLDER line (0.1 while 0.2.0 exists): :0.1.1 and :0.1 move, :latest does not.
pom 0.1.1; run refs/tags/v0.1.1 "${O1}"
r=0; [ "${RC}" -eq 0 ] && has 'channel=stable' && has 'latest=false' \
  && tags_are "${IMG}:0.1.1,${IMG}:sha-${O1:0:12},${IMG}:0.1" && lacks ':latest' || r=1
check "stable v0.1.1 on release/0.1 (older line) pushes :0.1.1 and :0.1 but not :latest" "${r}"

# 11b. sort -V, not string order: 0.10 is newer than 0.9.
g tag v0.10.0 "${R1}"
g update-ref refs/remotes/origin/release/0.9 "${R2}"
pom 0.9.1; run refs/tags/v0.9.1 "${R2}"
r=0; [ "${RC}" -eq 0 ] && has 'latest=false' && lacks ':latest' || r=1
check "v0.9.1 is an older line than an existing v0.10.0 (version sort)" "${r}"
g tag -d v0.10.0 >/dev/null; g update-ref -d refs/remotes/origin/release/0.9

# 11c. prereleases never count as a stable line: a v0.3.0-beta.1 tag does not stop 0.2.1 being latest.
g tag v0.3.0-beta.1 "${M3}"
pom 0.2.1; run refs/tags/v0.2.1 "${R2}"
r=0; [ "${RC}" -eq 0 ] && has 'latest=true' || r=1
check "a beta tag of a newer line does not take :latest from a stable" "${r}"
g tag -d v0.3.0-beta.1 >/dev/null

# 12. stable tag on a main-only commit is refused.
pom 0.2.1; run refs/tags/v0.2.1 "${M3}"
r=0; [ "${RC}" -ne 0 ] && has 'not on release/0.2' && lacks 'kind=' || r=1
check "stable v0.2.1 on a main commit (not on release/0.2) is refused" "${r}"

# 12b. stable tag on another line's release branch is refused.
pom 0.2.1; run refs/tags/v0.2.1 "${O1}"
r=0; [ "${RC}" -ne 0 ] && has 'not on release/0.2' && lacks 'kind=' || r=1
check "stable v0.2.1 on a release/0.1 commit is refused" "${r}"

# 12c. stable tag when release/X.Y does not exist at all is refused.
pom 0.4.0; run refs/tags/v0.4.0 "${M3}"
r=0; [ "${RC}" -ne 0 ] && has 'release/0.4 is not in the local checkout' && lacks 'kind=' || r=1
check "stable v0.4.0 with no release/0.4 branch is refused" "${r}"

# 12d. a stable patch older than one already published on its line is refused.
g tag v0.2.5 "${R2}"
pom 0.2.3; run refs/tags/v0.2.3 "${R2}"
r=0; [ "${RC}" -ne 0 ] && has 'would move backwards' && lacks 'kind=' || r=1
check "stable v0.2.3 after v0.2.5 exists is refused" "${r}"
g tag -d v0.2.5 >/dev/null

# 13. beta tag on a release-branch-only commit is refused.
pom 0.2.1-beta.1; run refs/tags/v0.2.1-beta.1 "${R2}"
r=0; [ "${RC}" -ne 0 ] && has 'not on main' && lacks 'kind=' || r=1
check "beta v0.2.1-beta.1 on a release/0.2 commit (not on main) is refused" "${r}"

# 13b. the guard fails closed when origin/main is missing from the checkout (shallow clone).
g update-ref -d refs/remotes/origin/main
pom 0.3.0-beta.1; run refs/tags/v0.3.0-beta.1 "${M3}"
r=0; [ "${RC}" -ne 0 ] && has 'fetch-depth: 0' && lacks 'kind=' || r=1
check "a checkout without origin/main is refused" "${r}"
g update-ref refs/remotes/origin/main "${M3}"

# 13c. a commit the checkout does not have is refused (fail closed, not "not an ancestor").
pom 0.3.0-beta.1; run refs/tags/v0.3.0-beta.1 "${SHA}"
r=0; [ "${RC}" -ne 0 ] && has 'not in the local checkout' && lacks 'kind=' || r=1
check "an unknown commit is refused" "${r}"

# 14. a push to release/X.Y is a main-style build: :sha-<12> only, never :main or a version.
pom 0.2.1; run refs/heads/release/0.2 "${R2}"
r=0; [ "${RC}" -eq 0 ] && has 'kind=branch' && has 'channel=none' && tags_are "${IMG}:sha-${R2:0:12}" || r=1
check "push to release/0.2 publishes :sha-<12> only" "${r}"

# 14b. a release/X.Y branch whose pom is on another line is refused.
pom 0.3.0-beta.1; run refs/heads/release/0.2 "${R2}"
r=0; [ "${RC}" -ne 0 ] && has 'not on the 0.2 line' && lacks 'kind=' || r=1
check "push to release/0.2 with pom 0.3.0-beta.1 is refused" "${r}"

if [ "${FAILURES}" -ne 0 ]; then
  printf '\ntest-release-version: %d failure(s)\n' "${FAILURES}" >&2
  exit 1
fi
printf '\ntest-release-version: all checks passed\n'
