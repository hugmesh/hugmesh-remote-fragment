package net.hugmesh.fragment.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import net.hugmesh.fragment.casdoor.CasdoorTokenClient;
import net.hugmesh.fragment.federation.FederationSyncClient;
import net.hugmesh.fragment.sync.FragmentSyncRunner;
import net.hugmesh.fragment.sync.FragmentSyncService;

import java.nio.file.Path;
import java.time.Duration;

/**
 * Wires the reactive sync pipeline from env-driven config. All
 * values have safe local defaults so a {@code ./mvnw test} / dev
 * boot comes up without Casdoor, federation, or a real nginx
 * volume.
 *
 * <p>Config (env var :: yaml key :: default):
 * <ul>
 *   <li>{@code FEDERATION_BASE_URL} :: {@code federation.base-url}
 *       :: cluster-local federation API URL — the federation API
 *       this fleet pulls FROM.</li>
 *   <li>{@code SYNC_TARGET_DIR} :: {@code sync.target-dir} ::
 *       {@code /usr/share/nginx/html} — the shared nginx root the
 *       bundles are written under
 *       ({@code <dir>/_fragments/<slug>/<hash>/...}).</li>
 *   <li>{@code SYNC_MODE} :: {@code sync.mode} :: {@code loop} —
 *       {@code once} (initContainer, one-shot then exit) or
 *       {@code loop} (sidecar).</li>
 *   <li>{@code SYNC_INTERVAL} :: {@code sync.interval} ::
 *       {@code PT60S} — the re-sync cadence in loop mode
 *       (ISO-8601 duration).</li>
 *   <li>{@code CASDOOR_TOKEN_URL} :: {@code casdoor.token-url} ::
 *       cluster-local Casdoor token endpoint —
 *       the client-credentials token endpoint.</li>
 *   <li>{@code CASDOOR_CLIENT_ID} :: {@code casdoor.client-id} ::
 *       {@code app-fragment-sync} — the machine-account OAuth
 *       app.</li>
 *   <li>{@code CASDOOR_CLIENT_SECRET} ::
 *       {@code casdoor.client-secret} :: (empty) — supplied from
 *       the {@code fragment-sync-casdoor} k8s Secret.</li>
 * </ul>
 */
@Configuration
public class SyncConfig {

    /**
     * Constructs the Casdoor service-account token client using the
     * configured token endpoint and machine-account credentials.
     *
     * @param tokenUrl     absolute URL of the Casdoor
     *                     client-credentials endpoint
     * @param clientId     Casdoor OAuth app ID (defaults to
     *                     {@code app-fragment-sync})
     * @param clientSecret Casdoor OAuth app secret injected from
     *                     the k8s Secret
     * @return the configured {@link CasdoorTokenClient}
     */
    @Bean
    CasdoorTokenClient casdoorTokenClient(
            @Value("${casdoor.token-url:"
                + "http://casdoor.hugmesh-pki.svc.cluster.local"
                + ":8000/api/login/oauth/access_token}")
            final String tokenUrl,
            @Value("${casdoor.client-id:app-fragment-sync}")
            final String clientId,
            @Value("${casdoor.client-secret:}")
            final String clientSecret) {
        return new CasdoorTokenClient(tokenUrl, clientId, clientSecret);
    }

    /**
     * Constructs the reactive federation read client, wired to the
     * federation API base URL and the token client that supplies
     * per-request bearer tokens.
     *
     * @param baseUrl the base URL of the hugmesh-federation-api
     *                service
     * @param tokens  the {@link CasdoorTokenClient} that
     *                mints/caches the service JWT
     * @return the configured {@link FederationSyncClient}
     */
    @Bean
    FederationSyncClient federationSyncClient(
            @Value("${federation.base-url:"
                + "http://hugmesh-federation-api"
                + ".hugmesh-backend.svc.cluster.local:8080}")
            final String baseUrl,
            final CasdoorTokenClient tokens) {
        return new FederationSyncClient(baseUrl, tokens);
    }

    /**
     * Constructs the sync engine that mirrors federation bundles
     * into the shared nginx volume root.
     *
     * @param federation the client used to fetch the manifest and
     *                   bundle files
     * @param targetDir  the nginx html root path on the shared
     *                   volume (defaults to
     *                   {@code /usr/share/nginx/html})
     * @return the configured {@link FragmentSyncService}
     */
    @Bean
    FragmentSyncService fragmentSyncService(
            final FederationSyncClient federation,
            @Value("${sync.target-dir:/usr/share/nginx/html}")
            final String targetDir) {
        return new FragmentSyncService(federation, Path.of(targetDir));
    }

    /**
     * Constructs the runner that drives sync on startup and (in
     * loop mode) on a periodic interval.
     *
     * <p>In {@code once} mode the runner performs a single blocking
     * sync, then calls {@link System#exit} so the JVM terminates
     * and the k8s initContainer completes — this is intentional
     * init-container semantics, not a crash.
     *
     * @param sync     the sync engine to drive
     * @param mode     {@code "once"} for init-container or
     *                 {@code "loop"} for sidecar (case-insensitive;
     *                 any value other than {@code "once"} activates
     *                 loop)
     * @param interval the re-sync cadence for loop mode (ISO-8601
     *                 duration, e.g. {@code PT60S})
     * @param context  Spring application context used to resolve
     *                 exit codes in once mode
     * @return the configured {@link FragmentSyncRunner}, disposed via
     *         {@link FragmentSyncRunner#stop()} on context shutdown
     *         so the loop-mode subscription is cancelled cleanly
     */
    @Bean(destroyMethod = "stop")
    FragmentSyncRunner fragmentSyncRunner(
            final FragmentSyncService sync,
            @Value("${sync.mode:loop}") final String mode,
            @Value("${sync.interval:PT60S}") final Duration interval,
            final ApplicationContext context) {
        FragmentSyncRunner.Mode m = "once".equalsIgnoreCase(mode)
                ? FragmentSyncRunner.Mode.ONCE
                : FragmentSyncRunner.Mode.LOOP;
        return new FragmentSyncRunner(sync, m, interval, context);
    }
}
