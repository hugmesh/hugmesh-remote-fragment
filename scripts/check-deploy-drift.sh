#!/usr/bin/env bash
# =============================================================================
# check-deploy-drift.sh — deploy/ vs central-manifest DRIFT guard.
# =============================================================================
# Asserts that this repo's `deploy/` Kustomize tree is still a VERBATIM copy of
# the reference cluster's central manifest, i.e.:
#
#   { deploy/*  }  ==  { hugmesh-generator specs/cluster/manifests/remote-fragment/* }
#
# byte for byte, in both directions.
#
# WHY THIS EXISTS. `deploy/` + hugmesh-deploy.yaml are the Forgejo-cutover half of
# a DUAL-HOME arrangement: the authoritative manifest still lives in
# hugmesh-generator, and `deploy/` is a hand-maintained copy of it that the live
# GitHub-rooted ArgoCD does not read. Nothing forced the two apart to stay
# together, and they drifted — silently, because the dark copy is never rendered
# by anything that runs. Three ways, all shipped:
#
#   * hugmesh-generator added a PodDisruptionBudget to every HA (HPA
#     minReplicas: 2) workload. `deploy/` got neither pdb.yaml nor the
#     `resources:` entry, so a cutover would have quietly deployed this HA pair
#     with NO disruption budget — a node drain could take both pods at once.
#     `kubectl kustomize deploy/` still exited 0; it just rendered one kind fewer.
#   * hugmesh-generator migrated the in-cluster registry from GitLab to Harbor.
#     `deploy/` still patched in the `gitlab-registry` imagePullSecret, which no
#     longer exists — every first-party pull would have been ImagePullBackOff.
#   * the Angular host repo was renamed hugmesh-systems-web -> hugmesh-host-shell
#     centrally; `deploy/nginx.conf` still named the dead one.
#
# A missing resource is the dangerous shape: kustomize cannot report the absence
# of a file nobody listed, so the only signal a dark copy can ever produce is a
# diff against its source. Hence this gate.
#
# CONTRACT. `deploy/` is a VERBATIM copy — there are no sanctioned local
# divergences, so the comparison needs no allowlist and no exceptions. Anything
# genuinely repo-specific belongs in the central manifest first, then here. That
# also means this guard fails on comment-only drift ON PURPOSE: the comments are
# how the next person learns which registry / which sibling repo is live, and a
# stale one is how the Harbor migration went unnoticed for as long as it did.
#
# Wired into .gitlab-ci.yml (lint stage, lint:deploy-manifest-drift), which
# sparse-clones the generator so the comparison genuinely runs, and into
# `make check-deploy-drift` for local use against a sibling checkout.
#
# Uses bash + diff + find. No cluster, no kubectl, no kustomize, no network of
# its own (the caller supplies the generator checkout).
#
# ENV
#   GENERATOR_ROOT                path to a hugmesh-generator checkout.
#                                 Default: ../hugmesh-generator (the sibling-
#                                 checkout convention the Makefile already uses
#                                 for the shared pentest harness).
#   DEPLOY_DRIFT_ALLOW_MISSING=1  opt OUT of the INCOMPLETE exit when there is no
#                                 generator checkout to compare against. Exits 0,
#                                 but the verdict line still says
#                                 `RESULT: PASS (INCOMPLETE ...)` — never a clean
#                                 PASS.
#
# EXIT CODES
#   0  PASS       — deploy/ matches the central manifest, and the comparison ran.
#   1  FAIL       — drift (offenders + unified diffs printed).
#   2  FATAL      — environment problem (deploy/ missing, no diff(1)).
#   3  INCOMPLETE — no drift found, but there was no central manifest to compare
#                   against. Non-zero on purpose: an unrun assertion is not a
#                   pass, and a guard that reports green having checked nothing
#                   is exactly the silence this file exists to end.
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "${SCRIPT_DIR}/.." && pwd)"

DEPLOY_DIR="${REPO_ROOT}/deploy"
GENERATOR_ROOT="${GENERATOR_ROOT:-${REPO_ROOT}/../hugmesh-generator}"
CENTRAL_REL="specs/cluster/manifests/remote-fragment"
CENTRAL_DIR="${GENERATOR_ROOT}/${CENTRAL_REL}"

say() { printf '%s\n' "$*"; }

# --- stage 0: environment -----------------------------------------------------
command -v diff >/dev/null 2>&1 || { say "FATAL: diff(1) not found."; exit 2; }

if [ ! -d "${DEPLOY_DIR}" ]; then
  say "FATAL: no deploy/ tree at ${DEPLOY_DIR}."
  exit 2
fi

# Relative paths, sorted, so the two sides are comparable as sets. -type f only:
# the central manifest is flat and so is deploy/. Deliberately POSIX find + sed
# rather than GNU `-printf`, so this runs unchanged under busybox (the CI image
# is alpine).
list_files() { ( cd "$1" && find . -type f | sed 's|^\./||' | LC_ALL=C sort ); }

DEPLOY_FILES="$(list_files "${DEPLOY_DIR}")"
if [ -z "${DEPLOY_FILES}" ]; then
  say "FATAL: deploy/ is empty — nothing to compare."
  exit 2
fi

if [ ! -d "${CENTRAL_DIR}" ]; then
  say "No central manifest at ${CENTRAL_DIR}"
  say "  (set GENERATOR_ROOT to a hugmesh-generator checkout, or clone it as a sibling)."
  if [ "${DEPLOY_DRIFT_ALLOW_MISSING:-0}" = "1" ]; then
    say "RESULT: PASS (INCOMPLETE — comparison SKIPPED via DEPLOY_DRIFT_ALLOW_MISSING=1; nothing was verified)"
    exit 0
  fi
  say "RESULT: INCOMPLETE — the drift comparison could not run. Not a pass."
  exit 3
fi

CENTRAL_FILES="$(list_files "${CENTRAL_DIR}")"
if [ -z "${CENTRAL_FILES}" ]; then
  say "FATAL: ${CENTRAL_DIR} exists but holds no files (a sparse checkout that did not materialize?)."
  exit 2
fi

say "deploy/  : ${DEPLOY_DIR}"
say "central  : ${CENTRAL_DIR}"
say ""

# --- stage 1: file SETS, both directions -------------------------------------
# The direction that bit: a file added centrally and never copied here. kustomize
# renders the smaller set happily, so only a set comparison can see it.
MISSING_HERE="$(LC_ALL=C comm -23 <(printf '%s\n' "${CENTRAL_FILES}") <(printf '%s\n' "${DEPLOY_FILES}"))"
EXTRA_HERE="$(LC_ALL=C comm -13 <(printf '%s\n' "${CENTRAL_FILES}") <(printf '%s\n' "${DEPLOY_FILES}"))"

fail=0

if [ -n "${MISSING_HERE}" ]; then
  fail=1
  say "DRIFT: present in the central manifest, MISSING from deploy/:"
  printf '%s\n' "${MISSING_HERE}" | sed 's/^/  - /'
  say "  -> copy each from ${CENTRAL_REL}/ and add it to deploy/kustomization.yaml resources:"
  say ""
fi

if [ -n "${EXTRA_HERE}" ]; then
  fail=1
  say "DRIFT: present in deploy/, ABSENT from the central manifest:"
  printf '%s\n' "${EXTRA_HERE}" | sed 's/^/  - /'
  say "  -> deploy/ is a verbatim copy; land the file centrally first, or delete it here."
  say ""
fi

# --- stage 2: byte parity of the files present on BOTH sides ------------------
BOTH="$(LC_ALL=C comm -12 <(printf '%s\n' "${CENTRAL_FILES}") <(printf '%s\n' "${DEPLOY_FILES}"))"
compared=0
while IFS= read -r f; do
  [ -n "${f}" ] || continue
  compared=$((compared + 1))
  if ! diff -u "${CENTRAL_DIR}/${f}" "${DEPLOY_DIR}/${f}" \
       --label "central/${f}" --label "deploy/${f}"; then
    fail=1
    say ""
  fi
done <<< "${BOTH}"

say ""
if [ "${fail}" -ne 0 ]; then
  say "RESULT: FAIL — deploy/ has drifted from ${CENTRAL_REL}/."
  say "  Re-sync deploy/ from the central manifest (it is a verbatim copy, not a fork)."
  exit 1
fi

say "RESULT: PASS — deploy/ matches ${CENTRAL_REL}/ byte for byte (${compared} files)."
exit 0
