# HugMesh remote-fragment — developer convenience targets.
#
# `make pentest` runs the SAME OWASP checks the CI pentest stage runs, locally:
#   * SCA  : OWASP Dependency-Check over this Maven project (mirrors pentest:sca /
#            the centralized .pentest-sca job). Fails only on a CVSS>=9 finding so a
#            local run is informative, not a wall.
#   * DAST  : delegates to the centralized harness (generator tests/pentest/
#            run-pentest.sh — the FULL probes+nuclei+ZAP baseline). The harness
#            stands up its own env-test-build (--env compose); opt in by setting
#            DAST_TARGET_URL to any value, otherwise it is skipped with a note.
#
# Self-contained + non-hard-failing: missing Docker / no opt-in / no harness are
# reported and skipped, never a hard error, so `make pentest` is safe to run
# anywhere. CI itself uses the .gitlab-ci.yml pentest stage, not this target.
#
# `make check-deploy-drift` runs the deploy/ vs central-manifest drift guard
# (scripts/check-deploy-drift.sh) that CI's lint:deploy-manifest-drift runs,
# against a sibling generator checkout. Unlike `pentest`, this one DOES fail:
# with no checkout to compare against it exits 3 (INCOMPLETE) rather than
# reporting green on an assertion that never ran.
#
# `make native` runs the GraalVM native build EXACTLY as CI's build:native job
# does -- both call scripts/native-build.sh, so there is one definition of the
# native build and a red pipeline is always reproducible here. It compiles the
# binary and then RUNS it TWICE against a stub federation + Casdoor -- once as
# the initContainer (SYNC_MODE=once, bytes on disk) and once as the sidecar
# (SYNC_MODE=loop, /actuator/health/readiness UP over HTTP) -- because a native
# image that merely compiles proves almost nothing: missing reflection metadata
# surfaces at first execution, not at build time. Like check-deploy-drift and
# unlike pentest, it DOES fail -- no GraalVM and no Docker is an error, never a
# silent skip.
#
# It needs a warm ~/.m2, for the same reason `./mvnw test` does: the private
# net.hugmesh:hugmesh-commons is not on Maven Central. When the build falls back
# into the GraalVM container that repo is bind-mounted in (the container runs
# with HOME=/tmp and would otherwise start from an empty one). If it is not
# there, the script says so up front instead of failing 30 s later inside Maven
# with "Could not find artifact ... in central" -- an error that names the one
# place the artifact was never going to be. Override with NATIVE_BUILD_M2, or
# set CI_JOB_TOKEN/CI_API_V4_URL/GITLAB_GROUP_ID to resolve from the registry.

SHELL := /bin/bash

# Public images used so this matches CI without bundling a runner image.
TEMURIN_IMAGE   ?= eclipse-temurin:25-jdk-noble
DEPCHECK_IMAGE  ?= owasp/dependency-check:latest
# GraalVM toolchain for `make native`, used only when the machine has no local
# GraalVM (no bin/native-image under GRAALVM_HOME/JAVA_HOME). Same pin as the
# build:native CI job.
GRAALVM_IMAGE   ?= ghcr.io/graalvm/graalvm-community:25

# Fail the local SCA build only on a critical (CVSS>=9) finding.
SCA_FAIL_CVSS   ?= 9

# Where to find the shared DAST harness. Defaults to a sibling checkout of the
# generator repo; override to a vendored/clone path. If absent and a target URL
# is set, `make pentest` prints the clone+run command instead of failing.
PENTEST_HARNESS_DIR ?= ../hugmesh-generator/tests/pentest

# Same sibling-checkout convention for the central deployment manifest that
# deploy/ is a verbatim copy of. Override to any hugmesh-generator checkout.
GENERATOR_ROOT ?= ../hugmesh-generator

.PHONY: pentest pentest-sca pentest-dast check-deploy-drift native native-compile

pentest: pentest-sca pentest-dast ## Run the local OWASP pen-test mirror (SCA + DAST)
	@echo "[pentest] done."

pentest-sca: ## OWASP Dependency-Check SCA over this Maven project (mirrors pentest:sca)
	@echo "[pentest:sca] OWASP Dependency-Check over $(CURDIR) (failOnCVSS=$(SCA_FAIL_CVSS))"
	@mkdir -p reports/sca .nvd-cache
	@if command -v docker >/dev/null 2>&1; then \
	  docker run --rm \
	    -v "$(CURDIR):/src:ro" \
	    -v "$(CURDIR)/.nvd-cache:/usr/share/dependency-check/data" \
	    -v "$(CURDIR)/reports/sca:/report" \
	    $(DEPCHECK_IMAGE) \
	    --project "remote-fragment" \
	    --scan /src \
	    --out /report \
	    --failOnCVSS $(SCA_FAIL_CVSS) \
	    --enableExperimental \
	    --format HTML --format JSON \
	    $${NVD_API_KEY:+--nvdApiKey "$$NVD_API_KEY"} \
	  || echo "[pentest:sca] dependency-check reported findings or could not complete (non-fatal locally; see reports/sca/)."; \
	else \
	  echo "[pentest:sca] docker not found — running the Maven plugin directly via ./mvnw."; \
	  ./mvnw -B org.owasp:dependency-check-maven:check -DfailBuildOnCVSS=$(SCA_FAIL_CVSS) \
	  || echo "[pentest:sca] dependency-check (maven plugin) reported findings or could not complete (non-fatal locally)."; \
	fi

pentest-dast: ## Full DAST harness (probes+nuclei+ZAP baseline) — delegates to the generator harness
	@if [ -z "$$DAST_TARGET_URL" ]; then \
	  echo "[pentest:dast] DAST_TARGET_URL not set — skipping. Set it (any value) to opt in to the local DAST run."; \
	  echo "[pentest:dast] the harness stands up its OWN env-test-build (--env compose), so no external URL is needed."; \
	elif ! command -v docker >/dev/null 2>&1; then \
	  echo "[pentest:dast] docker not found — skipping (the harness spins compose + pulls the zaproxy image)."; \
	elif [ -x "$(PENTEST_HARNESS_DIR)/run-pentest.sh" ]; then \
	  echo "[pentest:dast] delegating to the centralized harness (--env compose --scan baseline --pipeline main)"; \
	  bash "$(PENTEST_HARNESS_DIR)/run-pentest.sh" --env compose --scan baseline --pipeline main \
	  || echo "[pentest:dast] harness reported findings or could not complete (non-fatal locally; see out/pentest/)."; \
	else \
	  echo "[pentest:dast] harness not found at $(PENTEST_HARNESS_DIR)/run-pentest.sh."; \
	  echo "[pentest:dast] clone the generator + run it, e.g.:"; \
	  echo "    git clone https://github.com/hugmesh/hugmesh-generator /tmp/sg"; \
	  echo "    bash /tmp/sg/tests/pentest/run-pentest.sh --env compose --scan baseline --pipeline main"; \
	fi

check-deploy-drift: ## Diff deploy/ against hugmesh-generator's central manifest (mirrors lint:deploy-manifest-drift)
	@GENERATOR_ROOT="$(GENERATOR_ROOT)" bash scripts/check-deploy-drift.sh

native: ## GraalVM native build + runtime smoke test (mirrors CI's build:native)
	@GRAALVM_IMAGE="$(GRAALVM_IMAGE)" bash scripts/native-build.sh

native-compile: ## Compile the native binary only, without the runtime smoke test
	@GRAALVM_IMAGE="$(GRAALVM_IMAGE)" bash scripts/native-build.sh --no-smoke
