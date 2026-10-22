#!/usr/bin/env bash
# Shape checks for .github/workflows/release.yml (the publish workflow). Pure text, no network,
# no YAML library needed (plain python3), so it runs the same on a hosted and a self-hosted runner.
#
# What it holds the workflow to:
#   * triggers: push to main, push of v* tags, workflow_dispatch; never pull_request,
#     pull_request_target or workflow_run (this workflow has packages:write and may run on the
#     self-hosted runner).
#   * every job uses the one publish runs-on line (RUNS_ON_PUBLISH only for main or a v* tag,
#     else ubuntu-latest); nothing reads the old RUNS_ON variable.
#   * no :latest tag anywhere; the image tags come only from the version guard's outputs.
#   * both image jobs refuse to overwrite a published version; the preflight package read
#     still gates the sync build; the GitHub Release is prerelease and never latest for a beta.
#
# Run: bash scripts/test-release-workflow.sh   (WORKFLOW=<path> to check another file)
set -euo pipefail

SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
WORKFLOW="${WORKFLOW:-${SCRIPT_DIR}/../.github/workflows/release.yml}"

python3 - "${WORKFLOW}" <<'PY'
import re
import sys

path = sys.argv[1]
text = open(path, encoding="utf-8").read()
lines = text.splitlines()
failures = 0


def check(desc, ok):
    global failures
    print(("ok   " if ok else "FAIL ") + desc)
    if not ok:
        failures += 1


def code(line):
    """The line without a trailing YAML comment (good enough for this file's plain style)."""
    return re.sub(r"\s+#.*$", "", line) if not line.lstrip().startswith("#") else ""


def block(start_re, indent):
    """Lines of the mapping whose key line matches start_re at the given indent."""
    out, inside = [], False
    for ln in lines:
        if re.match(r"^" + " " * indent + start_re, ln):
            inside = True
            continue
        if inside:
            if ln.strip() and not ln.startswith(" " * (indent + 1)) and not ln.lstrip().startswith("#"):
                break
            out.append(ln)
    return out


PUBLISH_RUNS_ON = (
    "runs-on: ${{ fromJSON(github.event_name != 'pull_request' && "
    "github.event_name != 'pull_request_target' && (github.ref == 'refs/heads/main' || "
    "startsWith(github.ref, 'refs/tags/v') || startsWith(github.ref, 'refs/tags/studio-v')) && "
    "vars.RUNS_ON_PUBLISH || '\"ubuntu-latest\"') }}"
)

# 1. triggers
on_block = "\n".join(code(l) for l in block(r"on:\s*$", 0))
check("trigger block found", bool(on_block.strip()))
for bad in ("pull_request", "pull_request_target", "workflow_run", "issue_comment"):
    check(f"no {bad} trigger", re.search(r"\b" + bad + r"\b", on_block) is None)
check("push to main", re.search(r"branches:\s*\[\s*main\s*\]", on_block) is not None)
check("push of v* tags only", re.search(r"tags:\s*\[\s*'v\*'\s*\]", on_block) is not None)
check("workflow_dispatch has no inputs (the ref decides)",
      re.search(r"workflow_dispatch:\s*\{\}\s*$", on_block, re.M) is not None)

# 2. jobs and their runs-on lines
jobs_block = block(r"jobs:\s*$", 0)
jobs = {}
current = None
for ln in jobs_block:
    m = re.match(r"^  ([A-Za-z0-9_-]+):\s*$", ln)
    if m:
        current = m.group(1)
        jobs[current] = []
    elif current:
        jobs[current].append(ln)
expected_jobs = {"version", "preflight", "build-sync", "build-nginx", "github-release"}
check(f"jobs are {sorted(expected_jobs)}", set(jobs) == expected_jobs)
for name, body in jobs.items():
    runs = [l.strip() for l in body if re.match(r"^    runs-on:", l)]
    check(f"job {name} uses the publish runs-on line", runs == [PUBLISH_RUNS_ON])
check("nothing reads vars.RUNS_ON (only RUNS_ON_PUBLISH)",
      re.search(r"vars\.RUNS_ON\b(?!_PUBLISH)", text) is None)
check("no literal self-hosted label", "self-hosted" not in "\n".join(code(l) for l in lines))

# 3. tags: never :latest, and only from the guard
body_code = "\n".join(code(l) for l in lines)
check("no :latest tag anywhere", re.search(r":latest\b|'\{0\}:latest'|value=latest", body_code) is None)
sync = "\n".join(jobs.get("build-sync", []))
nginx = "\n".join(jobs.get("build-nginx", []))
check("sync image tags come from the guard",
      re.search(r"tags:\s*\$\{\{\s*needs\.version\.outputs\.sync_tags\s*\}\}", sync) is not None)
check("nginx image tags come from the guard",
      re.search(r"tags:\s*\$\{\{\s*needs\.version\.outputs\.nginx_tags\s*\}\}", nginx) is not None)
ver = "\n".join(jobs.get("version", []))
check("version job runs the guard for both images",
      ver.count("run: bash scripts/release-version.sh") == 2
      and "IMAGE: ${{ env.SYNC_IMAGE }}" in ver and "IMAGE: ${{ env.NGINX_IMAGE }}" in ver)
check("version job runs the guard tests",
      "bash scripts/test-release-version.sh" in ver and "bash scripts/test-release-workflow.sh" in ver)

# 4. overwrite refusal, preflight, release flags
for name, b in (("build-sync", sync), ("build-nginx", nginx)):
    check(f"{name} refuses to overwrite a published version on a release run",
          "docker buildx imagetools inspect" in b
          and "if: needs.version.outputs.kind == 'release'" in b
          and "already exists in GHCR" in b)
    check(f"{name} has packages: write", "packages: write" in b)
check("sync build needs the preflight package read",
      re.search(r"needs:\s*\[version,\s*preflight\]", sync) is not None)
check("nginx build runs after the sync build",
      re.search(r"needs:\s*\[version,\s*build-sync\]", nginx) is not None)
pre = "\n".join(jobs.get("preflight", []))
check("preflight still checks build-config, parent and commons",
      "for upstream in hugmesh-build-config hugmesh-parent hugmesh-commons" in pre)
rel = "\n".join(jobs.get("github-release", []))
check("github-release runs only for a release",
      "if: needs.version.outputs.kind == 'release'" in rel)
check("github-release marks a beta prerelease and never latest",
      "--prerelease --latest=false" in rel and "--verify-tag" in rel)
check("only github-release has contents: write",
      [n for n, b in jobs.items() if "contents: write" in "\n".join(b)] == ["github-release"])
check("workflow-level permissions are contents: read only",
      re.search(r"^permissions:\n  contents: read\n(?! )", text, re.M) is not None)

if failures:
    print(f"\ntest-release-workflow: {failures} failure(s)", file=sys.stderr)
    sys.exit(1)
print("\ntest-release-workflow: all checks passed")
PY
