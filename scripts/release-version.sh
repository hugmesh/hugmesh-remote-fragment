#!/usr/bin/env bash
# ─────────────────────────────────────────────────────────────────────────────
# release-version.sh — the version guard that runs first in .github/workflows/release.yml.
#
# It decides WHAT a release run is allowed to publish, from these inputs only:
#   * GITHUB_REF  (refs/heads/main, refs/heads/release/X.Y, or refs/tags/v<version>)
#   * GITHUB_SHA  (the commit being built)
#   * the <project><version> in pom.xml (the single version source of this repo)
#   * the local git checkout (RELEASE_GIT_DIR, default the repository root): the
#     remote-tracking branches origin/main and origin/release/X.Y and the v* tags. The
#     workflow checks out with fetch-depth: 0 so they exist; a missing one fails closed.
#
# THE TAG DECIDES THE CHANNEL. There are exactly two release tag shapes:
#
#   tag                 channel  must be on      image tags pushed
#   vX.Y.Z-beta.N       beta     origin/main     :X.Y.Z-beta.N, :sha-<12>, :beta
#   vX.Y.Z              stable   origin/         :X.Y.Z, :sha-<12>, :X.Y, and :latest ONLY when
#                                release/X.Y     X.Y is the newest stable line (computed from
#                                                this repo's own vA.B.C tags, ignoring the one
#                                                being released, with sort -V). A patch on an
#                                                older line moves :X.Y but never :latest.
#   anything else       refused  (an -rc.N or any other prerelease has no channel)
#
#   branch push               kind    image tags pushed
#   refs/heads/main           main    :main, :sha-<12>
#   refs/heads/release/X.Y    branch  :sha-<12> only (pom.xml must be X.Y.something)
#   anything else             refused (exit 1)
#
# "On a branch" means the tagged commit is an ancestor of (or equal to) that branch's tip.
# A stable tag whose commit is only on main is refused; a beta tag whose commit is only on a
# release branch is refused. A stable vX.Y.Z is also refused when a higher vX.Y.W already
# exists (:X.Y would move backwards).
#
# This repo publishes TWO images from one version (the sync sidecar and the nginx server).
# release.yml runs this guard once per image, with IMAGE set to each image name; kind,
# version, sha12, channel and latest are the same for both, only the tag list differs.
#
# It also refuses: a -SNAPSHOT version, a version that is not MAJOR.MINOR.PATCH with an
# optional -prerelease part (no '+build': OCI tags cannot hold '+'), and a tag whose name is
# not exactly "v" + the pom version. A published version is never overwritten; that check
# needs registry access, so it lives in the image job, not here. :X.Y.Z is immutable; the
# floating :beta, :X.Y and :latest move.
#
# Writes kind, version, sha12, tags (comma-separated, for docker/build-push-action), channel
# (beta|stable|none) and latest (true|false) to $GITHUB_OUTPUT when that is set, and always
# prints them. No network, no Maven.
#
# Test: bash scripts/test-release-version.sh
# ─────────────────────────────────────────────────────────────────────────────
set -euo pipefail

SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
POM="${RELEASE_POM:-${SCRIPT_DIR}/../pom.xml}"
GIT_DIR_="${RELEASE_GIT_DIR:-${SCRIPT_DIR}/..}"
IMAGE="${IMAGE:?IMAGE must be set, for example ghcr.io/hugmesh/hugmesh-remote-fragment}"
REF="${GITHUB_REF:?GITHUB_REF must be set}"
SHA="${GITHUB_SHA:?GITHUB_SHA must be set}"

die() {
  printf '::error::%s\n' "$*" >&2
  exit 1
}

# <project><version>, NOT the first <version> in the file (that one is the parent's).
version="$(python3 - "${POM}" <<'PY'
import sys
import xml.etree.ElementTree as ET
ns = {"m": "http://maven.apache.org/POM/4.0.0"}
el = ET.parse(sys.argv[1]).getroot().find("m:version", ns)
print("" if el is None or el.text is None else el.text.strip())
PY
)"

[ -n "${version}" ] || die "no <project><version> in ${POM}."
case "${version}" in
  *-SNAPSHOT) die "pom.xml version is ${version}. A SNAPSHOT is never published; set a release or beta version first." ;;
esac
version_re='^[0-9]+\.[0-9]+\.[0-9]+(-[0-9A-Za-z]+(\.[0-9A-Za-z]+)*)?$'
if ! [[ "${version}" =~ ${version_re} ]]; then
  die "pom.xml version '${version}' is not MAJOR.MINOR.PATCH[-prerelease] (for example 0.2.0-beta.1). No '+build' part: OCI tags cannot hold '+'."
fi

sha_re='^[0-9a-f]{12,}$'
if ! [[ "${SHA}" =~ ${sha_re} ]]; then
  die "GITHUB_SHA '${SHA}' is not a commit id."
fi
sha12="${SHA:0:12}"

# The tagged commit, peeled (an annotated tag object resolves to its commit).
tagged_commit() {
  git -C "${GIT_DIR_}" rev-parse -q --verify "${SHA}^{commit}" 2>/dev/null \
    || die "commit ${SHA} is not in the local checkout (${GIT_DIR_}). The workflow must check out with fetch-depth: 0."
}

# on_branch <commit> <branch>: 0 when <commit> is on origin/<branch>; dies when that
# remote-tracking branch is absent (fail closed: no branch, no release).
on_branch() {
  local c="$1"
  local b="$2"
  local ref="refs/remotes/origin/${b}"
  git -C "${GIT_DIR_}" rev-parse -q --verify "${ref}^{commit}" >/dev/null 2>&1 \
    || die "branch ${b} is not in the local checkout (no ${ref}). It does not exist on origin, or the workflow did not check out with fetch-depth: 0. Refusing."
  git -C "${GIT_DIR_}" merge-base --is-ancestor "${c}" "${ref}"
}

beta_tag_re='^v([0-9]+)\.([0-9]+)\.([0-9]+)-beta\.[0-9]+$'
stable_tag_re='^v([0-9]+)\.([0-9]+)\.([0-9]+)$'
release_branch_re='^release/([0-9]+)\.([0-9]+)$'
channel=none
latest=false

case "${REF}" in
  refs/heads/main)
    kind=main
    tags="${IMAGE}:main,${IMAGE}:sha-${sha12}"
    ;;
  refs/heads/release/*)
    branch="${REF#refs/heads/}"
    [[ "${branch}" =~ ${release_branch_re} ]] \
      || die "release branch ${branch} is not named release/X.Y (for example release/0.2). Nothing is published."
    bxy="${BASH_REMATCH[1]}.${BASH_REMATCH[2]}"
    case "${version}" in
      "${bxy}".*) ;;
      *) die "pom.xml version ${version} is not on the ${bxy} line, but this is ${branch}. A release/X.Y branch carries X.Y.* only." ;;
    esac
    kind=branch
    tags="${IMAGE}:sha-${sha12}"
    ;;
  refs/tags/v*)
    tag="${REF#refs/tags/}"
    [ "${tag}" = "v${version}" ] || die "tag ${tag} does not match pom.xml version ${version}. Tag the commit v${version}, or change pom.xml in a PR first."
    kind=release
    tags="${IMAGE}:${version},${IMAGE}:sha-${sha12}"
    commit="$(tagged_commit)"
    if [[ "${tag}" =~ ${beta_tag_re} ]]; then
      channel=beta
      on_branch "${commit}" main \
        || die "beta tag ${tag} is on commit ${commit}, which is not on main. A beta (vX.Y.Z-beta.N) is cut from main only; a release/X.Y branch publishes stable vX.Y.Z tags."
      tags="${tags},${IMAGE}:beta"
    elif [[ "${tag}" =~ ${stable_tag_re} ]]; then
      channel=stable
      major="${BASH_REMATCH[1]}"
      minor="${BASH_REMATCH[2]}"
      patch="${BASH_REMATCH[3]}"
      xy="${major}.${minor}"
      on_branch "${commit}" "release/${xy}" \
        || die "stable tag ${tag} is on commit ${commit}, which is not on release/${xy}. A stable vX.Y.Z is cut from its release/X.Y branch only (never from main, never from another line's branch)."
      # Newest stable line, from this repo's own vA.B.C tags, ignoring the one being released.
      lines=("${xy}")
      while IFS= read -r t; do
        [[ "${t}" =~ ${stable_tag_re} ]] || continue
        [ "${t}" = "${tag}" ] && continue
        lines+=("${BASH_REMATCH[1]}.${BASH_REMATCH[2]}")
        if [ "${BASH_REMATCH[1]}.${BASH_REMATCH[2]}" = "${xy}" ] && (( 10#${BASH_REMATCH[3]} > 10#${patch} )); then
          die "stable ${t} already exists on the ${xy} line, so ${tag} is older: :${xy} would move backwards. Fix forward with a higher patch."
        fi
      done < <(git -C "${GIT_DIR_}" tag -l 'v*')
      newest="$(printf '%s\n' "${lines[@]}" | sort -V | tail -n 1)"
      tags="${tags},${IMAGE}:${xy}"
      if [ "${newest}" = "${xy}" ]; then
        latest=true
        tags="${tags},${IMAGE}:latest"  # a stable on the newest line, the only case that moves :latest
      fi
    else
      die "tag ${tag} names no release channel. Release tags are vX.Y.Z-beta.N (beta, from main) or vX.Y.Z (stable, from release/X.Y); any other prerelease (-rc.N, ...) is refused."
    fi
    ;;
  *)
    die "release runs only on refs/heads/main, refs/heads/release/X.Y or a refs/tags/v<version> tag, not ${REF}. A dispatch from any other branch or tag publishes nothing."
    ;;
esac

printf 'kind=%s\nversion=%s\nsha12=%s\ntags=%s\nchannel=%s\nlatest=%s\n' "${kind}" "${version}" "${sha12}" "${tags}" "${channel}" "${latest}"
if [ -n "${GITHUB_OUTPUT:-}" ]; then
  printf 'kind=%s\nversion=%s\nsha12=%s\ntags=%s\nchannel=%s\nlatest=%s\n' "${kind}" "${version}" "${sha12}" "${tags}" "${channel}" "${latest}" >> "${GITHUB_OUTPUT}"
fi
