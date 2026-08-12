package net.hugmesh.fragment;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * hugmesh-remote-fragment — the sync side of the
 * sync-and-serve-static fragment fleet (frontend namespace, behind
 * the fe-gateway).
 *
 * <p>This reactive WebFlux service is the FIRST-PARTY container in
 * the remote-fragment pod. It mints a Casdoor SERVICE JWT
 * (client-credentials grant), pulls the ENABLED fragment set from
 * the hugmesh-federation-api manifest/bundles API, and writes
 * each content-addressed bundle into a shared nginx root so the
 * co-located stock {@code nginxinc/nginx-unprivileged} container
 * serves it STATICALLY (no per-request proxy to federation —
 * immutable, cacheable, HPA-able).
 *
 * <p>It runs in two modes off {@code SYNC_MODE}: {@code once} = an
 * init-container one-shot full sync then exit 0 (so nginx starts
 * with content); {@code loop} (default) = a long-running periodic
 * sidecar that re-syncs on an interval, so the fleet tracks
 * federation's kill-switch (disabled/circuit-broken fragments drop
 * out of the manifest and get pruned).
 *
 * <p>It has NO database. The federation manifest IS the source of
 * truth; the local nginx root is an ephemeral content-addressed
 * mirror re-derived on every pod start. RBAC/visibility stays
 * enforced upstream at the gateway/federation layer — this service
 * only ever receives (and serves) the bytes its service token is
 * allowed to see.
 */
@SpringBootApplication
public final class RemoteFragmentApplication {

    /** Utility class — instantiation prevented. */
    private RemoteFragmentApplication() { }

    /**
     * Application entry point.
     *
     * @param args standard Spring Boot command-line arguments
     *             (e.g. {@code --server.port=})
     */
    public static void main(final String[] args) {
        SpringApplication.run(RemoteFragmentApplication.class, args);
    }
}
