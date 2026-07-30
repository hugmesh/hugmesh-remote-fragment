package systems.hugmesh.fragment.sync;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.ApplicationContext;
import org.springframework.context.event.EventListener;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Drives {@link FragmentSyncService} on startup and (in loop mode)
 * on a periodic reactive interval. Mode is selected by
 * {@code sync.mode}:
 *
 * <ul>
 *   <li><b>once</b> — init-container behaviour: do ONE full sync,
 *       then exit the JVM with status 0 (1 on failure) so the
 *       pod's initContainer completes and nginx starts with content
 *       already on the shared volume.</li>
 *   <li><b>loop</b> (default) — sidecar behaviour: an initial
 *       sync, then re-sync every {@code sync.interval}. Each tick
 *       is independent and self-healing — a failed manifest fetch
 *       logs and the previous on-disk state is kept until the next
 *       good tick (so a transient federation blip never empties
 *       the fleet).</li>
 * </ul>
 *
 * <p>The interval uses {@code onErrorResume} per tick so one
 * failure never tears down the whole subscription (a naked
 * {@code Flux.interval(...).flatMap(...)} would terminate on the
 * first error).
 *
 * @see FragmentSyncService
 */
public class FragmentSyncRunner {

    /** Logger for this class. */
    private static final Logger LOG =
            LoggerFactory.getLogger(FragmentSyncRunner.class);

    /**
     * Sync execution mode, selected by the {@code sync.mode}
     * configuration property.
     */
    public enum Mode {
        /**
         * Run a single sync, then call {@link System#exit} so the
         * JVM terminates with a process exit code. Used as an
         * init-container: nginx starts only after this container
         * exits 0 with content already on the shared volume.
         */
        ONCE,

        /**
         * Run an initial sync on startup, then re-sync on a
         * periodic interval indefinitely. Used as a long-running
         * sidecar so the fleet tracks the federation kill-switch
         * in real time.
         */
        LOOP
    }

    /** The sync engine driven by this runner. */
    private final FragmentSyncService sync;

    /** Whether to run once (init-container) or loop (sidecar). */
    private final Mode mode;

    /** Re-sync cadence used in loop mode. */
    private final Duration interval;

    /** Spring context used to resolve exit codes in once mode. */
    private final ApplicationContext context;

    /** Handle to the active loop subscription; null in once mode. */
    private final AtomicReference<Disposable> loop =
            new AtomicReference<>(null);

    /**
     * @param syncService   the sync engine to drive
     * @param syncMode      {@link Mode#ONCE} for init-container or
     *                      {@link Mode#LOOP} for sidecar
     * @param syncInterval  re-sync cadence for loop mode (ignored
     *                      in once mode)
     * @param appContext    Spring application context used to
     *                      resolve exit codes in once mode
     */
    public FragmentSyncRunner(
            final FragmentSyncService syncService,
            final Mode syncMode,
            final Duration syncInterval,
            final ApplicationContext appContext) {
        this.sync = syncService;
        this.mode = syncMode;
        this.interval = syncInterval;
        this.context = appContext;
    }

    /**
     * Fires on {@link ApplicationReadyEvent} — the earliest point
     * at which the readiness probe infrastructure is wired up and a
     * sync result can be published.
     *
     * <p>In {@link Mode#ONCE}: runs a single blocking sync, then
     * calls {@link System#exit} so the JVM terminates and the k8s
     * initContainer lifecycle completes.
     *
     * <p>In {@link Mode#LOOP}: uses a {@code Flux.concat} trick to
     * fire the first tick immediately (by emitting {@code 0L} up
     * front) then switch to a regular {@code Flux.interval} for
     * subsequent ticks — the default {@code Flux.interval} alone
     * would delay the first emission by one full interval period.
     * Each tick wraps the sync in {@code onErrorResume} so a single
     * failed tick logs a warning and the subscription continues
     * with the next interval.
     */
    @EventListener(ApplicationReadyEvent.class)
    public void start() {
        LOG.info("[sync] starting in {} mode (interval={})",
                mode, interval);
        if (mode == Mode.ONCE) {
            int code = runOnceBlocking();
            LOG.info("[sync] once-mode complete, exiting with"
                    + " status {}", code);
            // initContainer semantics: exit so nginx starts with
            // content present.
            System.exit(SpringApplication
                    .exit(context, () -> code));
            return;
        }
        // Loop mode: emit 0L immediately for the first sync, then
        // switch to the regular interval so subsequent ticks fire
        // every sync.interval.
        Disposable d = Flux.concat(
                        Mono.just(0L),
                        Flux.interval(interval, interval))
                // Drop ticks that pile up behind a slow sync.
                .onBackpressureDrop()
                // concatMap keeps ticks sequential — only one sync
                // runs at a time.
                .concatMap(tick -> sync.syncOnce()
                        .then()
                        // Swallow per-tick errors so the loop
                        // subscription survives.
                        .onErrorResume(e -> {
                            LOG.warn("[sync] tick failed, will"
                                    + " retry next interval: {}",
                                    e.toString());
                            return Mono.empty();
                        }))
                .subscribe();
        loop.set(d);
    }

    /**
     * Run a single blocking sync; used in {@link Mode#ONCE} where
     * a blocking call is acceptable because the JVM exits
     * immediately after.
     *
     * @return {@code 0} on success, {@code 1} if the sync throws
     */
    int runOnceBlocking() {
        try {
            var r = sync.syncOnce().block();
            LOG.info("[sync] one-shot result: {}", r);
            return 0;
        } catch (Exception e) {
            LOG.error("[sync] one-shot sync failed: {}",
                    e.toString(), e);
            return 1;
        }
    }

    /**
     * Cancel the periodic loop subscription, if active. No-op in
     * once mode or if the subscription has already been disposed.
     */
    public void stop() {
        var d = loop.getAndSet(null);
        if (d != null && !d.isDisposed()) {
            d.dispose();
        }
    }
}
