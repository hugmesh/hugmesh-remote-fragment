#!/usr/bin/env bash
# ─────────────────────────────────────────────────────────────────────────────
# release-version.sh — the version guard that runs first in .github/workflows/release.yml.
#
# It decides WHAT a release run is allowed to publish, from two inputs only:
#   * GITHUB_REF  (refs/heads/main, or refs/tags/v<version>)
#   * the <project><version> in pom.xml (the single version source of this repo)
#
#   ref                     kind     image tags pushed
#   refs/heads/main         main     :main, :sha-<12>
#   refs/tags/v<version>    release  :<version>, :sha-<12>, plus :beta for a -beta.N
#                                    version (or :latest for a final version, no '-')
#   anything else           refused (exit 1)
#
# This repo publishes TWO images from one version (the sync sidecar and the nginx server).
# release.yml runs this guard once per image, with IMAGE set to each image name; the kind,
# version and sha12 are the same for both, only the tag list differs.
#
# It also refuses: a -SNAPSHOT version, a version that is not MAJOR.MINOR.PATCH with an
# optional -prerelease part (no '+build': OCI tags cannot hold '+'), and a tag whose name is
# not exactly "v" + the pom version. A published version is never overwritten; that check
# needs registry access, so it lives in the image job, not here.
#
# Writes kind, version, sha12 and tags (comma-separated, for docker/build-push-action) to
# $GITHUB_OUTPUT when that is set, and always prints them. Pure text: no network, no Maven.
#
# Test: bash scripts/test-release-version.sh
# ─────────────────────────────────────────────────────────────────────────────
set -euo pipefail

SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
POM="${RELEASE_POM:-${SCRIPT_DIR}/../pom.xml}"
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

case "${REF}" in
  refs/heads/main)
    kind=main
    tags="${IMAGE}:main,${IMAGE}:sha-${sha12}"
    ;;
  refs/tags/v*)
    tag="${REF#refs/tags/}"
    [ "${tag}" = "v${version}" ] || die "tag ${tag} does not match pom.xml version ${version}. Tag the commit v${version}, or change pom.xml in a PR first."
    kind=release
    tags="${IMAGE}:${version},${IMAGE}:sha-${sha12}"
    case "${version}" in
      *-beta.*) tags="${tags},${IMAGE}:beta" ;;
      *-*) ;;  # another prerelease (rc): no moving tag
      *) tags="${tags},${IMAGE}:latest" ;;  # a final release, the only case that moves :latest
    esac
    ;;
  *)
    die "release runs only on refs/heads/main or a refs/tags/v<version> tag, not ${REF}. A dispatch from any other branch or tag publishes nothing."
    ;;
esac

printf 'kind=%s\nversion=%s\nsha12=%s\ntags=%s\n' "${kind}" "${version}" "${sha12}" "${tags}"
if [ -n "${GITHUB_OUTPUT:-}" ]; then
  printf 'kind=%s\nversion=%s\nsha12=%s\ntags=%s\n' "${kind}" "${version}" "${sha12}" "${tags}" >> "${GITHUB_OUTPUT}"
fi
