package systems.hugmesh.fragment.sync;

import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.stereotype.Component;

/**
 * Readiness gate: the pod is only READY once at least one manifest
 * sync has succeeded, so the k8s readiness probe holds traffic off
 * the co-located nginx until there is content on the shared volume
 * to serve.
 *
 * <p>Contributes to {@code /actuator/health/readiness} (it is a
 * readiness-group member via application.yml). Liveness is
 * unaffected — a long federation outage should NOT kill the pod
 * (it keeps serving the last-good bytes), so this indicator is
 * deliberately readiness-only.
 *
 * @see FragmentSyncService#lastResult()
 * @see FragmentSyncService.SyncResult
 */
@Component("firstSync")
public class FirstSyncHealthIndicator implements HealthIndicator {

    /** The sync service polled to decide readiness. */
    private final FragmentSyncService sync;

    /**
     * @param syncService the sync service whose
     *                    {@link FragmentSyncService#lastResult()}
     *                    is polled to decide readiness
     */
    public FirstSyncHealthIndicator(
            final FragmentSyncService syncService) {
        this.sync = syncService;
    }

    /**
     * Returns the health status for the readiness probe.
     *
     * <p>Returns {@link Health#down()} with a {@code reason} detail
     * if no sync has completed yet — the pod is not ready to serve
     * traffic because the shared nginx volume has no content.
     * Returns {@link Health#up()} with sync counters once the first
     * manifest sync succeeds, indicating nginx has content to serve.
     *
     * @return {@code DOWN} (awaiting first sync) or {@code UP} with
     *         fragment/sync/deduped/failed/pruned counters from the
     *         most recent completed sync run
     */
    @Override
    public Health health() {
        FragmentSyncService.SyncResult r = sync.lastResult();
        // No sync has completed yet — block readiness until nginx
        // has content.
        if (r == null) {
            return Health.down()
                    .withDetail("reason",
                            "awaiting first manifest sync")
                    .build();
        }
        // At least one sync succeeded — surface the tally so
        // operators can see the current fleet state in the
        // readiness response body.
        return Health.up()
                .withDetail("fragments", r.fragments())
                .withDetail("synced", r.synced())
                .withDetail("deduped", r.deduped())
                .withDetail("failed", r.failed())
                .withDetail("pruned", r.pruned())
                .build();
    }
}
