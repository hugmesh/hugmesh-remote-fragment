#!/usr/bin/env python3
"""Tests for .github/workflows/release.yml. Runs the workflow's own steps, extracted from the
file: the signref check and the Release flags against each channel, and the overwrite guard,
the floating-tag re-check, the digest check and the GitHub Release under bash -e, the way GitHub
runs them, with docker and gh stubbed and a throwaway git origin. Both image jobs (build-sync and
build-nginx) are checked step by step. A step gets only the env it declares (plus the runner's
GITHUB_* variables and the workflow-level env), so a variable missing from a step's env: block
fails here instead of on the real runner. Standard library only (no PyYAML), no network.
Run: python3 scripts/test-release-workflow.py
"""
import hashlib
import json
import os
import re
import subprocess
import sys
import tempfile
import textwrap

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
WF = open(os.path.join(ROOT, ".github", "workflows", "release.yml"), encoding="utf-8").read()
SYNC_IMG = "ghcr.io/hugmesh/hugmesh-remote-fragment"
NGINX_IMG = "ghcr.io/hugmesh/hugmesh-remote-fragment/nginx"
REPOSITORY = "hugmesh/hugmesh-remote-fragment"
FAILURES = 0


def check(desc, ok, detail=""):
    global FAILURES
    if ok:
        print(f"ok   {desc}")
    else:
        FAILURES += 1
        print(f"FAIL {desc}\n---- {detail}\n----", file=sys.stderr)


def job(name):
    """The text of one job, from its '  name:' line to the next job."""
    m = re.search(r"^  " + re.escape(name) + r":\n(.*?)(?=^  [a-z][a-z-]*:\n|\Z)", WF, re.M | re.S)
    if not m:
        raise SystemExit(f"job {name!r} not found in release.yml")
    return m.group(1)


def step_block(job_text, needle):
    """The text of the step that contains needle, from its '- name:' line to the next step."""
    lines = job_text.splitlines()
    for i, line in enumerate(lines):
        if needle in line:
            start = i
            while not lines[start].lstrip().startswith("- name:"):
                start -= 1
            end = i + 1
            while end < len(lines) and not lines[end].lstrip().startswith("- name:"):
                end += 1
            return "\n".join(lines[start:end])
    raise SystemExit(f"step {needle!r} not found")


def step_env(block):
    """The step's env: block as {name: raw value}."""
    lines = block.splitlines()
    out = {}
    for i, line in enumerate(lines):
        m = re.match(r"^(\s*)env:\s*$", line)
        if m:
            ind = len(m.group(1))
            for raw in lines[i + 1:]:
                if raw.strip() and len(raw) - len(raw.lstrip()) <= ind:
                    break
                kv = re.match(r"^\s+([A-Z_][A-Z0-9_]*):\s*(.*)$", raw)
                if kv:
                    out[kv.group(1)] = kv.group(2).strip()
            break
    return out


def run_body(block):
    """The run: | block of a step, dedented."""
    lines = block.splitlines()
    for i, line in enumerate(lines):
        if re.fullmatch(r"\s*run: \|", line):
            indent = len(line) - len(line.lstrip())
            body = []
            for raw in lines[i + 1:]:
                if raw.strip() and len(raw) - len(raw.lstrip()) <= indent:
                    break
                body.append(raw)
            return textwrap.dedent("\n".join(body)) + "\n"
    raise SystemExit("step has no run: block:\n" + block)


# Workflow-level env (every step sees it) and the runner's own variables.
WF_ENV = {}
m = re.search(r"^env:\n((?:  [A-Z_]+: .*\n)+)", WF, re.M)
for line in (m.group(1).splitlines() if m else []):
    k, _, v = line.strip().partition(": ")
    WF_ENV[k] = v
check("workflow env names both images", WF_ENV.get("SYNC_IMAGE") == SYNC_IMG and WF_ENV.get("NGINX_IMAGE") == NGINX_IMG,
      str(WF_ENV))
RUNNER = ("GITHUB_REF", "GITHUB_SHA", "GITHUB_REF_NAME", "GITHUB_REPOSITORY", "GITHUB_OUTPUT")
STUBS = ("STUB_LOG", "STUB_REGISTRY", "STUB_RELEASE_EXISTS")

JOBS = {"build-sync": (SYNC_IMG, "sync_tags", "Build and push sync sidecar image"),
        "build-nginx": (NGINX_IMG, "nginx_tags", "Build and push nginx image")}

# ── the signref python, exactly as the workflow runs it ───────────────────────────────
DIGEST = "sha256:" + "a" * 64


def run_signref(py, img, version, channel, guard_tags, pushed_tags=None):
    pushed = guard_tags if pushed_tags is None else pushed_tags
    with tempfile.NamedTemporaryFile("r", delete=False) as out:
        path = out.name
    env = {k: v for k, v in os.environ.items() if not k.startswith(("GITHUB_", "RELEASE_"))}
    env.update({
        "METADATA": json.dumps({"image.name": ",".join(f"{img}:{t}" for t in pushed)}),
        "DIGEST": DIGEST,
        "EXPECTED_TAGS": ",".join(f"{img}:{t}" for t in guard_tags),
        "CHANNEL": channel,
        "GITHUB_OUTPUT": path,
    })
    if version is not None:
        env["VERSION"] = version
    r = subprocess.run([sys.executable, "-I", "-c", py], env=env, capture_output=True, text=True)
    written = open(path).read()
    os.unlink(path)
    return r.returncode, r.stdout + r.stderr, written


SIGNREF_CASES = [
    # (description, version, channel, guard tags, pushed tags or None, should pass)
    ("beta: :X.Y.Z-beta.N, :sha, :beta is signed", "0.3.0-beta.1", "beta",
     ["0.3.0-beta.1", "sha-0123456789ab", "beta"], None, True),
    ("stable newest line: :X.Y.Z, :sha, :X.Y, latest is signed", "0.3.0", "stable",
     ["0.3.0", "sha-0123456789ab", "0.3", "latest"], None, True),
    ("stable older line: :X.Y.Z, :sha, :X.Y (no latest) is signed", "0.2.4", "stable",
     ["0.2.4", "sha-0123456789ab", "0.2"], None, True),
    ("main build: :main, :sha is signed", "0.3.0-beta.1", "none",
     ["main", "sha-0123456789ab"], None, True),
    ("release branch build: :sha only is signed", "0.2.4", "none",
     ["sha-0123456789ab"], None, True),
    ("beta that pushed latest is refused", "0.3.0-beta.1", "beta",
     ["0.3.0-beta.1", "sha-0123456789ab", "beta", "latest"], None, False),
    ("stable that pushed :beta is refused", "0.3.0", "stable",
     ["0.3.0", "sha-0123456789ab", "0.3", "beta"], None, False),
    ("beta that pushed a floating :X.Y is refused", "0.3.0-beta.1", "beta",
     ["0.3.0-beta.1", "sha-0123456789ab", "beta", "0.3"], None, False),
    ("release-branch build that pushed latest is refused", "0.2.4", "none",
     ["sha-0123456789ab", "latest"], None, False),
    ("latest with VERSION missing is refused (fails closed)", None, "stable",
     ["0.3.0", "sha-0123456789ab", "0.3", "latest"], None, False),
    ("latest with VERSION empty (an unset job output) is refused (fails closed)", "", "stable",
     ["0.3.0", "sha-0123456789ab", "0.3", "latest"], None, False),
    ("a pushed tag the guard did not choose is refused", "0.3.0", "stable",
     ["0.3.0", "sha-0123456789ab", "0.3"], ["0.3.0", "sha-0123456789ab", "0.3", "latest"], False),
    ("a guard tag that was not pushed is refused", "0.3.0", "stable",
     ["0.3.0", "sha-0123456789ab", "0.3", "latest"], ["0.3.0", "sha-0123456789ab", "0.3"], False),
]

for name, (img, out_name, _) in JOBS.items():
    signref = step_block(job(name), "id: signref")
    m = re.search(r"python3 - <<'PY'\n(.*?)\n\s*PY\n", signref + "\n", re.S)
    py = textwrap.dedent(m.group(1)) if m else ""
    check(f"{name}: signref step holds a python heredoc", bool(py))
    check(f"{name}: signref keeps the literal `\"latest\" in tags`", '"latest" in tags' in py)
    env = step_env(signref)
    check(f"{name}: signref env passes VERSION, CHANNEL and its own image's EXPECTED_TAGS",
          env.get("VERSION") == "${{ needs.version.outputs.version }}"
          and env.get("CHANNEL") == "${{ needs.version.outputs.channel }}"
          and env.get("EXPECTED_TAGS") == f"${{{{ needs.version.outputs.{out_name} }}}}", str(env))
    for desc, version, channel, guard_tags, pushed, should_pass in SIGNREF_CASES:
        rc, out, written = run_signref(py, img, version, channel, guard_tags, pushed)
        if should_pass:
            check(f"{name}: signref: {desc}", rc == 0 and f"digest={DIGEST}" in written and f"repo={img}" in written, out)
        else:
            check(f"{name}: signref: {desc}", rc != 0 and "digest=" not in written, out)

# ── the GitHub Release flags, exactly as the workflow computes them ───────────────────
RELEASE_JOB = job("github-release")
RELEASE_STEP = "Create the GitHub Release (beta prerelease never latest; stable latest only on the newest line)"
release = step_block(RELEASE_JOB, f"- name: {RELEASE_STEP}")
m = re.search(r"\n(\s*case \"\$\{CHANNEL\}:\$\{LATEST\}:\$\{VERSION\}\" in\n.*?\n\s*esac)\n", release, re.S)
CASE = textwrap.dedent(m.group(1)) if m else ""
check("github-release step decides flags from CHANNEL, LATEST and VERSION", bool(CASE))
renv = step_env(release)
for var in ("CHANNEL", "LATEST"):
    check(f"github-release step env passes {var}", renv.get(var) == f"${{{{ needs.version.outputs.{var.lower()} }}}}", str(renv))


def flags(channel, latest, version):
    script = "set -euo pipefail\nflags=()\n" + CASE + "\nprintf '%s\\n' \"${flags[@]}\"\n"
    env = dict(os.environ, CHANNEL=channel, LATEST=latest, VERSION=version)
    r = subprocess.run(["bash", "-c", script], env=env, capture_output=True, text=True)
    return r.returncode, r.stdout.split()


for desc, args, want in [
    ("beta -> prerelease, not latest", ("beta", "false", "0.3.0-beta.1"), ["--prerelease", "--latest=false"]),
    ("stable newest line -> full release marked Latest", ("stable", "true", "0.3.0"), ["--latest"]),
    ("stable older line -> full release, not Latest", ("stable", "false", "0.2.4"), ["--latest=false"]),
]:
    rc, got = flags(*args)
    check(f"release flags: {desc}", rc == 0 and got == want, f"rc={rc} got={got}")
for desc, args in [
    ("beta marked latest is refused", ("beta", "true", "0.3.0-beta.1")),
    ("beta channel with a final version is refused", ("beta", "false", "0.3.0")),
    ("stable channel with a prerelease version is refused", ("stable", "true", "0.3.0-beta.1")),
    ("no channel is refused", ("none", "false", "0.3.0")),
    ("empty channel is refused", ("", "", "0.3.0")),
]:
    rc, got = flags(*args)
    check(f"release flags: {desc}", rc != 0, f"rc={rc} got={got}")

# ── triggers, checkout depth, version job ─────────────────────────────────────────────
check("push runs on main and release/**", re.search(r"^\s+branches: \[main, 'release/\*\*'\]$", WF, re.M) is not None)
check("tag pushes still run on v*", re.search(r"^\s+tags: \['v\*'\]$", WF, re.M) is not None)
check("no pull_request trigger", re.search(r"^\s+pull_request(_target)?:", WF, re.M) is None)
check("the release-channels contract marker is present", "hugmesh-release-channels: beta+stable" in WF)
VERSION_JOB = job("version")
check("version job checks out with fetch-depth: 0",
      re.search(r"uses: actions/checkout@v4\n\s+with:\n\s+fetch-depth: 0\n", VERSION_JOB) is not None)
check("version job runs the guard tests, this test and the guard for both images",
      "bash scripts/test-release-version.sh" in VERSION_JOB and "python3 scripts/test-release-workflow.py" in VERSION_JOB
      and VERSION_JOB.count("run: bash scripts/release-version.sh") == 2)
for out in ("channel", "latest"):
    check(f"version job exports {out}", f"{out}: ${{{{ steps.sync.outputs.{out} }}}}" in VERSION_JOB)
agree = step_block(VERSION_JOB, "- name: The two guard runs agree")
check("version job refuses when the two guard runs disagree on kind, version, channel or latest",
      all(f"steps.{s}.outputs.{k}" in agree for s in ("sync", "nginx") for k in ("kind", "version", "channel", "latest")))
vnames = re.findall(r"^\s+- name: (.*)$", VERSION_JOB, re.M)
check("the agreement check runs after both guard runs",
      vnames.index("The two guard runs agree") > max(vnames.index("Version guard, sync image"),
                                                     vnames.index("Version guard, nginx image")), str(vnames))

# ── the shell steps, run the way GitHub runs them ─────────────────────────────────────
# A step with no shell: key runs as `bash -e {0}`, and `set -uo pipefail` does not turn -e off.
check("release.yml sets no shell/defaults (these tests assume GitHub's bash -e {0})",
      re.search(r"^\s*(defaults|shell):", WF, re.M) is None)

TMP = tempfile.TemporaryDirectory()
WORK = TMP.name
BIN = os.path.join(WORK, "bin")
os.makedirs(BIN)
LOG = os.path.join(WORK, "stub.log")


def stub(name, script):
    path = os.path.join(BIN, name)
    with open(path, "w", encoding="utf-8") as f:
        f.write(script)
    os.chmod(path, 0o755)


def resolve(value):
    """A step env value: ${{ env.X }} resolves from the workflow env; anything else is the test's."""
    m = re.fullmatch(r"\$\{\{ env\.([A-Z_]+) \}\}", value)
    return WF_ENV.get(m.group(1)) if m else None


def run_step(job_text, step_name, given, cwd=WORK):
    """Runs the step under bash -e with only what GitHub would give it: the workflow env, the
    runner's GITHUB_* variables, the step's own env: block (env.* values resolved, the rest from
    `given`), and the stubs. Returns (rc, combined output, lines the stubs logged)."""
    block = step_block(job_text, f"- name: {step_name}")
    declared = step_env(block)
    stray = sorted(k for k in given if k not in declared and k not in RUNNER and k not in STUBS)
    if stray:
        raise SystemExit(f"test bug: {step_name!r} does not declare {stray}")
    path = os.path.join(WORK, "step.sh")
    with open(path, "w", encoding="utf-8") as f:
        f.write(run_body(block))
    open(LOG, "w").close()
    full = {k: v for k, v in os.environ.items() if not k.startswith(("GITHUB_", "RELEASE_")) and k != "IMAGE"}
    full.update(WF_ENV)
    full.update({"PATH": BIN + os.pathsep + os.environ["PATH"], "STUB_LOG": LOG})
    for k, v in declared.items():
        r = resolve(v)
        if r is not None:
            full[k] = r
    full.update(given)
    r = subprocess.run(["bash", "--noprofile", "--norc", "-e", path], cwd=cwd, env=full, capture_output=True, text=True)
    return r.returncode, r.stdout + r.stderr, open(LOG).read().splitlines()


# A registry stub. STUB_REGISTRY maps a ref to its manifest body, or to an error text. `inspect
# REF` prints a listing whose Digest is the body's sha256; `inspect REF --raw` prints the body's
# exact bytes, so `--raw | sha256sum` gives the digest the way the real registry does.
stub("docker", r"""#!/usr/bin/env python3
import hashlib, json, os, sys
a = sys.argv[1:]
with open(os.environ["STUB_LOG"], "a") as log:
    log.write(" ".join(a) + "\n")
if a[:3] != ["buildx", "imagetools", "inspect"] or len(a) not in (4, 5) or (len(a) == 5 and a[4] != "--raw"):
    sys.exit(f"stub docker: unexpected args {a}")
ref = a[3]
got = json.loads(os.environ["STUB_REGISTRY"]).get(ref, "ERROR: " + ref + ": not found")
if got.startswith("ERROR"):
    print(got, file=sys.stderr)
    sys.exit(1)
if len(a) == 5:
    sys.stdout.write(got)
else:
    print(f"Name:      {ref}\nMediaType: application/vnd.oci.image.index.v1+json\nDigest:    sha256:{hashlib.sha256(got.encode()).hexdigest()}\n")
""")

BODY = '{"schemaVersion":2,"mediaType":"application/vnd.oci.image.index.v1+json","manifests":[]}'
OTHER_BODY = BODY.replace("[]", '[{"x":1}]')
SIGNED = "sha256:" + hashlib.sha256(BODY.encode()).hexdigest()
OTHER = "sha256:" + hashlib.sha256(OTHER_BODY.encode()).hexdigest()

for name, (img, out_name, build_name) in JOBS.items():
    jt = job(name)

    # 1. "Refuse to overwrite a published version"
    ow = step_block(jt, "- name: Refuse to overwrite a published version")
    check(f"{name}: overwrite guard inspects only the immutable :<version>",
          'ref="${IMAGE}:${VERSION}"' in ow and "docker buildx imagetools inspect" in ow
          and not re.search(r":(latest|beta)\b|latest\"|\$\{IMAGE\}:[0-9]", "\n".join(
              l for l in ow.split("run: |", 1)[1].splitlines() if not l.lstrip().startswith("#"))))

    def overwrite_step(channel, version, registry):
        return run_step(jt, "Refuse to overwrite a published version",
                        {"VERSION": version, "CHANNEL": channel, "STUB_REGISTRY": json.dumps(registry)})

    for channel, version in [("beta", "0.3.0-beta.1"), ("stable", "0.3.0")]:
        ref = f"{img}:{version}"
        for desc, answer in [("not found", None),
                             ("MANIFEST_UNKNOWN", "ERROR: failed to authorize: MANIFEST_UNKNOWN: manifest unknown"),
                             ("name unknown", "ERROR: name unknown")]:
            rc, out, calls = overwrite_step(channel, version, {} if answer is None else {ref: answer})
            check(f"{name}: overwrite step, {channel}: {desc} on a first publish continues under bash -e",
                  rc == 0 and "is not published yet; continuing" in out and calls == [f"buildx imagetools inspect {ref}"],
                  f"rc={rc} calls={calls} out={out}")
        rc, out, _ = overwrite_step(channel, version, {ref: BODY})
        want = "fix forward" if channel == "stable" else "raise the -beta.N"
        check(f"{name}: overwrite step, {channel}: a published version is refused with the {channel} message",
              rc != 0 and "already exists in GHCR" in out and want in out and "continuing" not in out, f"rc={rc} out={out}")
        rc, out, _ = overwrite_step(channel, version, {ref: "ERROR: failed to authorize: denied: denied"})
        check(f"{name}: overwrite step, {channel}: an unclassified registry answer fails closed",
              rc != 0 and "could not tell whether" in out and "denied" in out and "continuing" not in out, f"rc={rc} out={out}")
    rc, out, calls = overwrite_step("stable", "0.3.0", {f"{img}:latest": BODY, f"{img}:0.3": BODY})
    check(f"{name}: overwrite step: floating :X.Y and latest existing do not stop a stable",
          rc == 0 and calls == [f"buildx imagetools inspect {img}:0.3.0"], f"rc={rc} calls={calls} out={out}")

    # 2. "Check every pushed tag resolves to the signed digest"
    pushed = [f"{img}:0.3.0", f"{img}:sha-0123456789ab", f"{img}:0.3", f"{img}:latest"]
    dblock = step_block(jt, "- name: Check every pushed tag resolves to the signed digest")
    denv = step_env(dblock)
    check(f"{name}: the digest check reads its own image's tags and the signed digest",
          "if: steps.signref.outcome == 'success'" in dblock
          and denv.get("TAGS") == f"${{{{ needs.version.outputs.{out_name} }}}}"
          and denv.get("DIGEST") == "${{ steps.signref.outputs.digest }}", str(denv))

    def digest_step(tags, registry):
        return run_step(jt, "Check every pushed tag resolves to the signed digest",
                        {"TAGS": ",".join(tags), "DIGEST": SIGNED, "STUB_REGISTRY": json.dumps(registry)})

    rc, out, calls = digest_step(pushed, {t: BODY for t in pushed})
    check(f"{name}: digest step: every tag on the signed digest passes, and each tag was asked with --raw",
          rc == 0 and [c.split()[3] for c in calls] == pushed and all(c.endswith(" --raw") for c in calls),
          f"rc={rc} calls={calls} out={out}")
    rc, out, _ = digest_step(pushed, dict({t: BODY for t in pushed}, **{f"{img}:latest": OTHER_BODY}))
    check(f"{name}: digest step: a tag on another digest is refused and named",
          rc != 0 and f"{img}:latest resolves to {OTHER}" in out, f"rc={rc} out={out}")
    rc, out, _ = digest_step(pushed, {t: BODY for t in pushed[:-1]})
    check(f"{name}: digest step: a pushed tag the registry cannot find is refused",
          rc != 0 and "could not read" in out, f"rc={rc} out={out}")
    rc, out, calls = digest_step([], {})
    check(f"{name}: digest step: no tags at all is refused, not a vacuous pass", rc != 0 and not calls,
          f"rc={rc} calls={calls} out={out}")

    # 3. wiring of this job
    names = re.findall(r"^\s+- name: (.*)$", jt, re.M)
    check(f"{name}: checks out with fetch-depth: 0",
          re.search(r"uses: actions/checkout@v4\n\s+with:\n\s+fetch-depth: 0\n", jt) is not None)
    check(f"{name}: the re-check is the last step before the build",
          "Re-check the floating tags against origin" in names
          and names.index("Re-check the floating tags against origin") + 1 == names.index(build_name), str(names))
    rblock = step_block(jt, "- name: Re-check the floating tags against origin")
    renv_ = step_env(rblock)
    check(f"{name}: the re-check runs on every release run, against its own image's answer",
          "if: needs.version.outputs.kind == 'release'" in rblock
          and resolve(renv_.get("IMAGE", "")) == img
          and renv_.get("WANT_TAGS") == f"${{{{ needs.version.outputs.{out_name} }}}}"
          and renv_.get("WANT_LATEST") == "${{ needs.version.outputs.latest }}", str(renv_))
    check(f"{name}: the image is pushed with the tags the re-check just confirmed",
          f"tags: ${{{{ needs.version.outputs.{out_name} }}}}" in step_block(jt, f"- name: {build_name}"))
    check(f"{name}: the digest check runs after the signature and both attestations",
          names.index("Attest the SBOM (pushed to the registry)") < names.index("Check every pushed tag resolves to the signed digest")
          and names.index("Sign the image digest (cosign keyless, GitHub OIDC)") < names.index("Check every pushed tag resolves to the signed digest"),
          str(names))
    check(f"{name}: the summary's cosign identity covers main, release/* and v* tags",
          "refs/(heads/main|heads/release/.*|tags/v.*)" in jt)

# ── a real origin and checkout, for the steps that ask the version guard again ────────
#   M ── main (pom 0.4.0-beta.1)      R ── release/0.3 (pom 0.3.0)      O ── release/0.2 (pom 0.2.1)
ORIGIN = os.path.join(WORK, "origin.git")
DEV = os.path.join(WORK, "dev")
POM = """<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0">
  <modelVersion>4.0.0</modelVersion>
  <artifactId>remote-fragment</artifactId>
  <version>%s</version>
</project>
"""


def git(*args, cwd=None):
    r = subprocess.run(["git", "-c", "user.name=t", "-c", "user.email=t@example.invalid", "-c", "commit.gpgsign=false",
                        "-c", "tag.gpgSign=false", "-c", "init.defaultBranch=main", *args],
                       cwd=cwd, capture_output=True, text=True)
    if r.returncode:
        raise SystemExit(f"git {args}: {r.stdout}{r.stderr}")
    return r.stdout.strip()


def commit_pom(version):
    with open(os.path.join(DEV, "pom.xml"), "w", encoding="utf-8") as f:
        f.write(POM % version)
    git("add", "-A", cwd=DEV)
    git("commit", "-q", "-m", version, cwd=DEV)
    return git("rev-parse", "HEAD", cwd=DEV)


git("init", "-q", "--bare", ORIGIN)
git("init", "-q", DEV)
git("remote", "add", "origin", ORIGIN, cwd=DEV)
os.makedirs(os.path.join(DEV, "scripts"))
with open(os.path.join(ROOT, "scripts", "release-version.sh"), encoding="utf-8") as src, \
        open(os.path.join(DEV, "scripts", "release-version.sh"), "w", encoding="utf-8") as dst:
    dst.write(src.read())
BASE = commit_pom("0.2.0-beta.1")
M = commit_pom("0.4.0-beta.1")
git("push", "-q", "origin", "HEAD:refs/heads/main", cwd=DEV)
git("checkout", "-q", "-b", "r03", BASE, cwd=DEV)
R = commit_pom("0.3.0")
git("push", "-q", "origin", "HEAD:refs/heads/release/0.3", cwd=DEV)
git("checkout", "-q", "-b", "r02", BASE, cwd=DEV)
O = commit_pom("0.2.1")
git("push", "-q", "origin", "HEAD:refs/heads/release/0.2", cwd=DEV)
TAG_COMMITS = {"v0.3.0": R, "v0.2.1": O, "v0.4.0-beta.1": M}
for tag_name, sha in TAG_COMMITS.items():
    git("tag", tag_name, sha, cwd=ORIGIN)
CLONES = 0


def checkout(sha):
    """What actions/checkout with fetch-depth: 0 leaves: every branch and tag, HEAD on sha."""
    global CLONES
    CLONES += 1
    ws = os.path.join(WORK, f"ws{CLONES}")
    git("clone", "-q", ORIGIN, ws)
    git("checkout", "-q", "--detach", sha, cwd=ws)
    return ws


def on_origin(*names, at=R):
    for name in names:
        git("tag", name, at, cwd=ORIGIN)


def off_origin(*names):
    for name in names:
        git("tag", "-d", name, cwd=ORIGIN)


def guard_env(tag_name):
    return {"GITHUB_REF": f"refs/tags/{tag_name}", "GITHUB_SHA": TAG_COMMITS[tag_name]}


def version_job(tag_name, ws, img):
    """The version job's answer for one image: the real guard's output, as the workflow passes it on."""
    full = dict(os.environ, IMAGE=img, GITHUB_OUTPUT="", **guard_env(tag_name))
    r = subprocess.run(["bash", "scripts/release-version.sh"], cwd=ws, env=full, capture_output=True, text=True)
    if r.returncode:
        raise SystemExit(f"fixture: the guard refused {tag_name}: {r.stderr}")
    return dict(line.split("=", 1) for line in r.stdout.splitlines())


# 4. "Re-check the floating tags against origin", in each image job.
RECHECK = "Re-check the floating tags against origin"
for name, (img, _, _) in JOBS.items():
    jt = job(name)

    def recheck(tag_name, ws, answer):
        return run_step(jt, RECHECK, dict(guard_env(tag_name), WANT_TAGS=answer["tags"], WANT_LATEST=answer["latest"]), cwd=ws)

    ws = checkout(R)
    answer = version_job("v0.3.0", ws, img)
    check(f"{name}: fixture: the version job says v0.3.0 moves :0.3 and latest",
          answer["latest"] == "true" and f"{img}:0.3," in answer["tags"] + ",", str(answer))
    rc, out, _ = recheck("v0.3.0", ws, answer)
    check(f"{name}: re-check: nothing changed on origin, the run continues",
          rc == 0 and "still correct" in out and f"{img}:0.3.0," in out, f"rc={rc} out={out}")
    rc, out, _ = recheck("v0.3.0", ws, dict(answer, latest="false"))
    check(f"{name}: re-check: the right tags with a different latest answer stop the run",
          rc != 0 and "Re-run all jobs" in out, f"rc={rc} out={out}")
    rc, out, _ = recheck("v0.3.0", ws, dict(answer, tags=answer["tags"].replace(f",{img}:latest", "")))
    check(f"{name}: re-check: the same latest answer with different tags stops the run",
          rc != 0 and "Re-run all jobs" in out, f"rc={rc} out={out}")

    on_origin("v0.4.0")
    rc, out, _ = recheck("v0.3.0", ws, answer)
    check(f"{name}: re-check: a newer stable line tagged since the version job stops a stale latest",
          rc != 0 and "Re-run all jobs" in out and f"{img}:latest" in out and "still correct" not in out, f"rc={rc} out={out}")
    rc, out, _ = recheck("v0.3.0", ws, version_job("v0.3.0", ws, img))
    check(f"{name}: re-check: after 'Re-run all jobs' the same tag goes through without latest",
          rc == 0 and "latest=false" in out and f"{img}:latest" not in out, f"rc={rc} out={out}")
    off_origin("v0.4.0")
    git("fetch", "-q", "--force", "--prune", "origin", "+refs/tags/*:refs/tags/*", cwd=ws)

    on_origin("v0.3.1")
    rc, out, _ = recheck("v0.3.0", ws, answer)
    check(f"{name}: re-check: a higher patch tagged since the version job stops :X.Y moving backwards",
          rc != 0 and "would move backwards" in out and "refuses it now" in out, f"rc={rc} out={out}")
    off_origin("v0.3.1")
    git("fetch", "-q", "--force", "--prune", "origin", "+refs/tags/*:refs/tags/*", cwd=ws)

    on_origin("v0.4.0")
    git("fetch", "-q", "--force", "origin", "+refs/tags/*:refs/tags/*", cwd=ws)
    stale_false = version_job("v0.3.0", ws, img)
    off_origin("v0.4.0")
    rc, out, _ = recheck("v0.3.0", ws, stale_false)
    check(f"{name}: re-check: a tag deleted from origin since the version job counts too (the fetch prunes)",
          rc != 0 and "Re-run all jobs" in out and "latest=true" in out, f"rc={rc} out={out}")

    ws = checkout(O)
    answer = version_job("v0.2.1", ws, img)
    check(f"{name}: fixture: the version job says the older-line v0.2.1 does not move latest", answer["latest"] == "false", str(answer))
    rc, out, _ = recheck("v0.2.1", ws, answer)
    check(f"{name}: re-check: an older-line patch still goes through, without latest", rc == 0, f"rc={rc} out={out}")

    ws = checkout(M)
    answer = version_job("v0.4.0-beta.1", ws, img)
    on_origin("v0.5.0")
    rc, out, _ = recheck("v0.4.0-beta.1", ws, answer)
    check(f"{name}: re-check: a beta does not depend on the stable tags", rc == 0, f"rc={rc} out={out}")
    off_origin("v0.5.0")

# 5. "Create the GitHub Release": gh stubbed, flags read back, the guard real.
stub("gh", r"""#!/usr/bin/env bash
printf '%s\n' "$*" >> "${STUB_LOG}"
if [ "$1 $2" = "release view" ]; then
  [ "${STUB_RELEASE_EXISTS:-}" = yes ]
  exit $?
fi
[ "$1 $2" = "release create" ] && exit 0
echo "stub gh: unexpected $*" >&2
exit 97
""")


def github_release(tag_name, channel, latest, *newer_tags, exists=""):
    """The job's checkout happens now, so it holds newer_tags; the image jobs' answer was `latest`."""
    on_origin(*newer_tags)
    ws = checkout(TAG_COMMITS[tag_name])
    off_origin(*newer_tags)
    rc, out, calls = run_step(RELEASE_JOB, RELEASE_STEP, dict(
        guard_env(tag_name), GH_TOKEN="x", VERSION=tag_name[1:], SHA12=TAG_COMMITS[tag_name][:12], CHANNEL=channel, LATEST=latest,
        GITHUB_REF_NAME=tag_name, GITHUB_REPOSITORY=REPOSITORY, STUB_RELEASE_EXISTS=exists), cwd=ws)
    created = [c for c in calls if c.startswith("release create")]
    return rc, out, created[0].split() if created else None


rc, out, made = github_release("v0.3.0", "stable", "true")
check("github-release: newest stable, nothing newer since: marked Latest",
      rc == 0 and made and "--latest" in made and "--latest=false" not in made, f"rc={rc} made={made} out={out}")
check("github-release: the notes name both images",
      made is not None and f"{SYNC_IMG}:0.3.0" in " ".join(made) and f"{NGINX_IMG}:0.3.0" in " ".join(made), f"made={made}")
rc, out, made = github_release("v0.3.0", "stable", "true", "v0.4.0")
check("github-release: a newer line tagged since the image jobs: created, not marked Latest",
      rc == 0 and made and "--latest=false" in made and "--latest" not in made and "::warning::" in out, f"rc={rc} made={made} out={out}")
rc, out, made = github_release("v0.3.0", "stable", "true", "v0.3.1")
check("github-release: a higher patch tagged since (the guard refuses): created, not marked Latest, not a red run",
      rc == 0 and made and "--latest=false" in made and "--latest" not in made and "would move backwards" in out, f"rc={rc} made={made} out={out}")
rc, out, made = github_release("v0.2.1", "stable", "false")
check("github-release: older-line patch: a full release, not marked Latest",
      rc == 0 and made and "--latest=false" in made and "--prerelease" not in made, f"rc={rc} made={made} out={out}")
off_origin("v0.3.0")  # the guard alone would now call v0.2.1 the newest line
rc, out, made = github_release("v0.2.1", "stable", "false")
on_origin("v0.3.0")
check("github-release: the fresh answer never marks Latest a release whose images did not move latest",
      rc == 0 and made and "--latest=false" in made and "--latest" not in made, f"rc={rc} made={made} out={out}")
rc, out, made = github_release("v0.4.0-beta.1", "beta", "false")
check("github-release: beta: prerelease, not latest",
      rc == 0 and made and "--prerelease" in made and "--latest=false" in made, f"rc={rc} made={made} out={out}")
rc, out, made = github_release("v0.3.0", "stable", "true", exists="yes")
check("github-release: an existing Release is left alone", rc == 0 and made is None and "not touching it" in out, f"rc={rc} made={made} out={out}")

check("github-release checks out with fetch-depth: 0, leaving no write token in the checkout",
      re.search(r"uses: actions/checkout@v4\n\s+with:\n\s+fetch-depth: 0\n\s+persist-credentials: false\n", RELEASE_JOB) is not None)

if FAILURES:
    print(f"\ntest-release-workflow: {FAILURES} failure(s)", file=sys.stderr)
    sys.exit(1)
print("\ntest-release-workflow: all checks passed")
