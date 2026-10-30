#!/usr/bin/env bash
# Shape checks for .github/workflows/release.yml (the publish workflow). Pure text, no network,
# no YAML library needed (plain python3), so it runs the same on a hosted and a self-hosted runner.
#
# What it holds the workflow to:
#   * triggers: push to main, push of v* tags, workflow_dispatch; never pull_request,
#     pull_request_target or workflow_run (this workflow has packages:write and may run on the
#     self-hosted runner).
#   * version, preflight and github-release use the one publish runs-on line (RUNS_ON_PUBLISH
#     only for main or a v* tag, else ubuntu-latest); nothing reads the old RUNS_ON variable.
#   * build-sync and build-nginx (build, push, sign) run on literal ubuntu-latest, never on a
#     self-hosted runner; each signs its pushed DIGEST with cosign keyless and pushes provenance
#     and SBOM attestations; only they hold id-token: write and attestations: write.
#   * notify-site is hosted, has no permissions and runs only for v* tags after the Release.
#   * no :latest tag anywhere; the image tags come only from the version guard's outputs.
#   * both image jobs refuse to overwrite a published version; the preflight package read
#     still gates the sync build; the GitHub Release is prerelease and never latest for a beta.
#   * that refusal step is EXECUTED here under the runner's default `bash -e`, docker stubbed:
#     image absent -> continues, present -> refuses, any other probe error -> fails closed.
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
expected_jobs = {"version", "preflight", "build-sync", "build-nginx", "github-release", "notify-site"}
check(f"jobs are {sorted(expected_jobs)}", set(jobs) == expected_jobs)
HOSTED = {"build-sync", "build-nginx", "notify-site"}
for name, body in jobs.items():
    runs = [l.strip() for l in body if re.match(r"^    runs-on:", l)]
    if name in HOSTED:
        check(f"job {name} runs on hosted ubuntu-latest, written literally", runs == ["runs-on: ubuntu-latest"])
    else:
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
    perms = re.search(r"^    permissions:\n((?:      [a-z-]+: [a-z]+\n)+)", b + "\n", re.M)
    check(f"{name} permissions are exactly contents read, packages/id-token/attestations write",
          perms is not None and sorted(perms.group(1).split("\n")[:-1]) == sorted([
              "      contents: read", "      packages: write", "      id-token: write", "      attestations: write"]))
    bc = "\n".join(code(l) for l in b.split("\n"))
    check(f"{name} resolves the digest from its build step, refusing latest and non-sha256",
          "id: build" in bc and "id: signref" in bc and bc.index("id: build") < bc.index("id: signref")
          and "DIGEST: ${{ steps.build.outputs.digest }}" in bc
          and 'sha256:[0-9a-f]{64}' in bc and '"latest" in tags' in bc)
    after = bc[bc.index("id: signref"):] if "id: signref" in bc else ""
    check(f"{name} signs the pushed digest with cosign keyless",
          'run: cosign sign --yes "$REF"' in after
          and "REF: ${{ steps.signref.outputs.repo }}@${{ steps.signref.outputs.digest }}" in after)
    check(f"{name} pushes provenance and SBOM attestations for that digest",
          "uses: actions/attest-build-provenance@" in after and "uses: actions/attest-sbom@" in after
          and after.count("subject-digest: ${{ steps.signref.outputs.digest }}") == 2
          and after.count("push-to-registry: true") == 2 and "push-to-registry: false" not in after)
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
check("only the two build jobs have id-token or attestations",
      sorted(n for n, b in jobs.items()
             if re.search(r"^\s+(id-token|attestations):", "\n".join(code(l) for l in b), re.M))
      == ["build-nginx", "build-sync"])
note = "\n".join(code(l) for l in jobs.get("notify-site", []))
check("notify-site has no permissions and runs only for v* tags after the Release",
      re.search(r"^    permissions: \{\}$", note, re.M) is not None
      and re.search(r"^    permissions:\s*$", note, re.M) is None
      and "if: startsWith(github.ref, 'refs/tags/v')" in note
      and "needs: [github-release]" in note)
check("workflow-level permissions are contents: read only",
      re.search(r"^permissions:\n  contents: read\n(?! )", text, re.M) is not None)

# 5. the overwrite refusal EXECUTES correctly under the runner's default shell. A step with no
#    shell: key runs as `bash -e {0}`, and `set -uo pipefail` does not turn -e off, so a bare
#    `out=$(probe); rc=$?` ends the step at the probe when the image is absent (every first
#    publish). Each image job's run block is run here under bash -e with docker stubbed.
import os
import subprocess
import tempfile


def run_block(script_text, env_extra, tmp):
    path = os.path.join(tmp, "step.sh")
    with open(path, "w", encoding="utf-8") as f:
        f.write(script_text)
    env = dict(os.environ)
    env.update(env_extra)
    p = subprocess.run(["bash", "--noprofile", "--norc", "-e", path], env=env,
                       stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True, timeout=60)
    return p.returncode, p.stdout


def step_run_block(body, step_name):
    """The dedented run: | block of the named step in a job's lines, or None."""
    start = None
    for i, ln in enumerate(body):
        if re.match(r"^\s*- name:\s*" + re.escape(step_name) + r"\s*$", ln):
            start = i
            break
    if start is None:
        return None, None
    step_indent = len(body[start]) - len(body[start].lstrip())
    shell_key = None
    for j in range(start + 1, len(body)):
        ln = body[j]
        if ln.strip() and len(ln) - len(ln.lstrip()) <= step_indent:
            return None, shell_key
        m = re.match(r"^(\s*)shell:\s*(.*)$", ln)
        if m:
            shell_key = m.group(2).strip()
        m = re.match(r"^(\s*)run:\s*\|\s*$", ln)
        if m:
            key_indent = len(m.group(1))
            out = []
            for k in range(j + 1, len(body)):
                b = body[k]
                if b.strip() and len(b) - len(b.lstrip()) <= key_indent:
                    break
                out.append(b)
            while out and not out[-1].strip():
                out.pop()
            ind = min(len(b) - len(b.lstrip()) for b in out if b.strip()) if out else 0
            return "\n".join(b[ind:] for b in out) + "\n", shell_key
    return None, shell_key


STUB = """#!/bin/sh
printf '%s\\n' "$*" > "${STUB_MARK}"
case "${STUB_MODE}" in
  absent)  echo "ERROR: ${4}: not found"; exit 1 ;;
  present) echo "Name: ${4}"; echo "MediaType: application/vnd.oci.image.index.v1+json"; exit 0 ;;
  denied)  echo "ERROR: failed to authorize: denied: denied"; exit 1 ;;
esac
echo "stub: unknown STUB_MODE" >&2; exit 99
"""

with tempfile.TemporaryDirectory(prefix="test-release-wf-") as tmp:
    rc, _ = run_block("set -uo pipefail\nout=$(false 2>&1)\nrc=$?\necho reached\n", {}, tmp)
    check("harness canary: the bare $(false); rc=$? form dies under the harness's bash -e", rc != 0)

    bindir = os.path.join(tmp, "bin")
    os.mkdir(bindir)
    with open(os.path.join(bindir, "docker"), "w", encoding="utf-8") as f:
        f.write(STUB)
    os.chmod(os.path.join(bindir, "docker"), 0o755)
    mark = os.path.join(tmp, "docker-argv")
    image, version = "ghcr.io/example/probe-image", "9.9.9-beta.1"

    for name in ("build-sync", "build-nginx"):
        blk, shell_key = step_run_block(jobs.get(name, []), "Refuse to overwrite a published version")
        check(f"{name}: overwrite-refusal run block found", bool(blk and blk.strip()))
        check(f"{name}: overwrite-refusal step runs under the default shell (bash -e)",
              shell_key in (None, "bash") and not re.search(r"^defaults:", text, re.M))
        if not blk:
            continue
        for mode, want_rc, want, not_want in (
                ("absent", 0, "is not published yet; continuing.", "::error::"),
                ("present", 1, "already exists in GHCR", "could not tell"),
                ("denied", 1, "could not tell whether", "already exists in GHCR")):
            if os.path.exists(mark):
                os.remove(mark)
            rc, out = run_block(blk, {"PATH": bindir + os.pathsep + os.environ.get("PATH", ""),
                                      "STUB_MODE": mode, "STUB_MARK": mark,
                                      "IMAGE": image, "VERSION": version}, tmp)
            argv = open(mark, encoding="utf-8").read().strip() if os.path.exists(mark) else None
            ok = (rc == want_rc and want in out and not_want not in out
                  and argv == f"buildx imagetools inspect {image}:{version}")
            check(f"{name}: under bash -e, image {mode} -> exit {want_rc} with '{want}'", ok)
            if not ok:
                print(f"     exit={rc} probe argv={argv!r}\n     " + out.replace("\n", "\n     "))

if failures:
    print(f"\ntest-release-workflow: {failures} failure(s)", file=sys.stderr)
    sys.exit(1)
print("\ntest-release-workflow: all checks passed")
PY
