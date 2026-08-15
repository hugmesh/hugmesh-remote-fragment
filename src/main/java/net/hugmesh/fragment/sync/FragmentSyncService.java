package net.hugmesh.fragment.sync;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.core.io.buffer.DataBufferUtils;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import net.hugmesh.fragment.federation.BundleMetadata;
import net.hugmesh.fragment.federation.FederationManifest;
import net.hugmesh.fragment.federation.FederationSyncClient;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermission;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * The sync engine: pull the ENABLED fragment set from the
 * federation manifest and mirror each content-addressed bundle
 * into {@code <root>/_fragments/<slug>/<hash>/} so the co-located
 * nginx serves it as static, immutable, cacheable files.
 *
 * <p>Guarantees (all exercised by
 * {@code FragmentSyncServiceTest}):
 * <ul>
 *   <li><b>Filter</b> — only {@code kind == "fragment"} remotes
 *       are synced (manifest already dropped
 *       disabled/circuit-open/invisible rows server-side, so the
 *       admin kill-switch is honoured automatically).</li>
 *   <li><b>Dedupe</b> — a {@code <slug>/<hash>} dir that already
 *       exists is content-addressed and immutable; it is skipped
 *       (NO re-fetch).</li>
 *   <li><b>Prune</b> — any {@code <slug>/<hash>} dir on disk that
 *       is no longer in the manifest is deleted
 *       (disabled/removed/kill-switched fragments vanish from the
 *       fleet).</li>
 *   <li><b>Resilience</b> — a single bad bundle is logged and
 *       skipped ({@code onErrorResume}); it never aborts the run
 *       or the periodic loop.</li>
 *   <li><b>Atomic write</b> — each file streams to a {@code .part}
 *       temp then is atomically moved into place; the
 *       {@code <slug>/<hash>} dir only appears complete once every
 *       file landed (a half-written dir is removed on error so a
 *       retry re-syncs cleanly).</li>
 *   <li><b>Index</b> — a top-level
 *       {@code /_fragments/index.json} maps
 *       {@code slug -> { contentHash, version, remoteEntryUrl,
 *       exposedModule, displayName, visibility }} for the host's
 *       loadRemoteModule.</li>
 * </ul>
 *
 * <p><b>Visibility model:</b> all fragments served by this fleet
 * are {@code kind == "fragment"} remotes. {@code public} fragments
 * are accessible to any caller with a valid session (ROLE_USER
 * floor is sufficient). The federation manifest server-side
 * already filters out {@code internal}, disabled, and
 * circuit-open fragments for this service account's authority
 * level, so this fleet never performs its own visibility checks —
 * it only syncs and serves the bytes that federation has already
 * authorised it to see.
 *
 * <p><b>Reactive marble — one full {@link #syncOnce()} run:</b>
 * <pre>
 *   federation.manifest()
 *     --[FederationManifest]--&gt; syncManifest(manifest)
 *         filter(isFragment)         // drop non-fragment kinds
 *         build desired (slug+hash) set
 *         ensureDir(fragmentsRoot())
 *         Flux.fromIterable(fragments)
 *               .concatMap(syncFragment)  // sequential: gentle
 *                   dirExists?  --yes--&gt; Outcome.DEDUPED
 *                               --no --&gt; fetchBundle(remote, dir)
 *                                         bundle(hash)     // metadata
 *                                         fetchFile(hash, rel, staging)
 *                                         promote(staging, finalDir)
 *                                        --&gt; Outcome.SYNCED
 *                   onErrorResume  --&gt; removeQuietly(dir),
 *                                      Outcome.FAILED
 *         .collectList()
 *         prune(desired)          // delete slug/hash not in manifest
 *         writeIndex(fragments)   // rewrite index.json atomically
 *         tally(...)              // assemble SyncResult
 *     doOnNext(last::set)         // update readiness indicator
 * </pre>
 *
 * <p>All blocking filesystem work is offloaded to
 * {@link Schedulers#boundedElastic()}; the WebFlux event loop is
 * never blocked.
 *
 * @see FederationSyncClient
 * @see FragmentSyncRunner
 * @see FirstSyncHealthIndicator
 */
public class FragmentSyncService {

    /** Logger for this class. */
    private static final Logger LOG =
            LoggerFactory.getLogger(FragmentSyncService.class);

    /**
     * Sub-directory name under the nginx root that holds the
     * content-addressed bundle mirror. Resolves to
     * {@code <root>/_fragments/}.
     */
    public static final String FRAGMENTS_DIR = "_fragments";

    /**
     * Default single entry file fetched when a bundle's
     * {@code manifestJson} is absent or {@code "{}"}. Reflects
     * today's single-file federation reality where only the
     * remote-entry descriptor is uploaded.
     */
    public static final String DEFAULT_ENTRY_FILE = "remoteEntry.json";

    /** Name of the top-level index file written after each sync run. */
    private static final String INDEX_FILE = "index.json";

    /**
     * Number of leading characters of a content hash used in
     * log messages to keep them concise.
     */
    private static final int SHORT_HASH_LENGTH = 12;

    /**
     * The reactive federation client used to fetch the manifest
     * and bundle files.
     */
    private final FederationSyncClient federation;

    /**
     * Absolute path to the nginx html root on the shared emptyDir
     * volume.
     */
    private final Path root;

    /**
     * Jackson mapper for parsing {@code manifestJson} and writing
     * {@code index.json}.
     */
    private final ObjectMapper mapper = new ObjectMapper();

    /**
     * Result of the most recent completed sync run. {@code null}
     * until the first sync finishes; read by
     * {@link FirstSyncHealthIndicator}.
     */
    private final AtomicReference<SyncResult> last =
            new AtomicReference<>(null);

    /**
     * @param federationClient the client used to fetch the
     *                         manifest, bundle metadata, and
     *                         individual bundle files from
     *                         hugmesh-federation-api
     * @param syncRoot         absolute path to the nginx html root
     *                         on the shared volume (e.g.
     *                         {@code /usr/share/nginx/html});
     *                         bundles are written under
     *                         {@code <syncRoot>/_fragments/<slug>/<hash>/}
     */
    public FragmentSyncService(
            final FederationSyncClient federationClient,
            final Path syncRoot) {
        this.federation = federationClient;
        this.root = syncRoot;
    }

    /**
     * Returns the result of the most recently completed sync run,
     * or {@code null} if no sync has completed yet. Used by
     * {@link FirstSyncHealthIndicator} to decide whether the pod
     * is ready to serve traffic.
     *
     * @return the last {@link SyncResult}, or {@code null} before
     *         the first sync
     */
    public SyncResult lastResult() {
        return last.get();
    }

    /**
     * Run one full sync: fetch the federation manifest, sync each
     * fragment bundle (dedupe + fetch), prune vanished bundles from
     * disk, then rewrite {@code index.json}.
     *
     * <p>Errors on individual bundles are logged and skipped
     * ({@code onErrorResume}) so the run completes with whatever
     * synced cleanly. A manifest-level failure propagates as an
     * error signal so the caller (loop tick / startup) can log it;
     * the previous good state on disk is preserved.
     *
     * <p>On success, the result is stored in {@link #last} so the
     * readiness indicator transitions to UP.
     *
     * @return a {@link Mono} emitting the {@link SyncResult} for
     *         this run; errors if the federation manifest fetch
     *         fails
     * @throws org.springframework.web.reactive.function.client
     *         .WebClientResponseException (propagated) if the
     *         federation server returns a non-2xx on the manifest
     *         call
     */
    public Mono<SyncResult> syncOnce() {
        // Fetch the manifest, then drive the full sync pipeline.
        return federation.manifest()
                .flatMap(this::syncManifest)
                // Store the result so FirstSyncHealthIndicator
                // can report UP.
                .doOnNext(last::set)
                .doOnNext(r -> LOG.info(
                        "[sync] done: {} fragment(s) [{} synced,"
                        + " {} deduped, {} skipped, {} failed],"
                        + " {} pruned",
                        r.fragments(), r.synced(),
                        r.deduped(), r.skipped(), r.failed(),
                        r.pruned()))
                // Log manifest-level failures but do not interfere
                // with error propagation.
                .doOnError(e -> LOG.warn(
                        "[sync] manifest sync failed"
                        + " (keeping prior state): {}",
                        e.toString()));
    }

    /**
     * Drive the per-manifest sync pipeline: filter to fragments,
     * build the desired key set, ensure the fragments root exists,
     * sync each fragment sequentially, prune stale dirs, and write
     * the index.
     *
     * @param manifest the federation manifest to sync from
     * @return a {@link Mono} emitting the {@link SyncResult} for
     *         this manifest
     */
    private Mono<SyncResult> syncManifest(
            final FederationManifest manifest) {
        // Keep only fragment-kind remotes; the manifest may include
        // other kinds.
        List<FederationManifest.Remote> fragments =
                manifest.remotes().stream()
                        .filter(FederationManifest.Remote::isFragment)
                        .toList();

        // Build the set of (slug, hash) keys that must NOT be pruned.
        Set<String> desired = fragments.stream()
                .map(r -> key(r.slug(), r.contentHash()))
                .collect(Collectors.toCollection(
                        LinkedHashSet::new));

        return ensureDir(fragmentsRoot())
                // Sync each fragment sequentially to stay gentle
                // on the federation server.
                .thenMany(Flux.fromIterable(fragments)
                        .concatMap(this::syncFragment))
                // Collect outcomes so we can tally after prune +
                // index write.
                .collectList()
                .flatMap(outcomes -> prune(desired)
                        .flatMap(pruned -> writeIndex(fragments)
                                .thenReturn(tally(
                                        fragments.size(),
                                        outcomes, pruned))));
    }

    /**
     * Sync one fragment's bundle; returns its {@link Outcome}.
     * Never emits an error — failures are caught by
     * {@code onErrorResume}, logged, and mapped to
     * {@link Outcome#FAILED} so a single bad bundle never aborts
     * the run.
     *
     * <p>If the content-addressed bundle directory already exists
     * on disk the bundle is immutable and identical, so it is
     * skipped ({@link Outcome#DEDUPED}).
     *
     * @param remote the manifest entry for this fragment
     * @return a {@link Mono} emitting {@link Outcome#SYNCED},
     *         {@link Outcome#DEDUPED}, or {@link Outcome#FAILED};
     *         never errors
     */
    private Mono<Outcome> syncFragment(
            final FederationManifest.Remote remote) {
        String slug = remote.slug();
        String hash = remote.contentHash();
        if (slug == null || slug.isBlank()) {
            // No slug = a malformed manifest entry. Nothing can be
            // done with it and nothing else will fix it, so this
            // stays a real, loudly-logged failure.
            LOG.warn("[sync] skipping fragment with missing"
                    + " slug: {}", remote);
            return Mono.just(Outcome.FAILED);
        }
        if (hash == null || hash.isBlank()) {
            // A NULL content hash is NOT an error: it is the
            // registry's documented "module registered, bundle not
            // yet synced" state (federation-api changelog
            // 015-vanity-profile-fragment.xml states it explicitly,
            // and resolve deliberately returns the descriptor with a
            // null fragmentPath so a host can tell the two apart).
            // Counting it as FAILED made a perfectly healthy module
            // report as a failure and WARN on EVERY 60s tick for the
            // lifetime of the process — on the local stack that was
            // half of a permanent "2 fragment(s) [0 synced, 0
            // deduped, 2 failed]". There is nothing to fetch yet, so
            // report it as its own outcome, quietly.
            LOG.debug("[sync] {} has no bundle yet"
                    + " (content hash unset) — nothing to fetch",
                    slug);
            return Mono.just(Outcome.SKIPPED);
        }
        Path dir = bundleDir(slug, hash);
        return dirExists(dir)
                .flatMap(exists -> {
                    if (exists) {
                        // Content-addressed + immutable => already
                        // have this exact bundle.
                        LOG.debug("[sync] dedupe {} @ {}"
                                + " (already present)",
                                slug, shortHash(hash));
                        return Mono.just(Outcome.DEDUPED);
                    }
                    // Directory absent — fetch and write the bundle
                    // for the first time.
                    return fetchBundle(remote, dir)
                            .thenReturn(Outcome.SYNCED);
                })
                .onErrorResume(e -> {
                    LOG.warn("[sync] fragment {} @ {} failed,"
                            + " skipping: {}",
                            slug, shortHash(hash), e.toString());
                    // Best-effort cleanup of a half-written dir so
                    // a later run retries clean.
                    return removeQuietly(dir)
                            .thenReturn(Outcome.FAILED);
                });
    }

    /**
     * Fetch every file of one bundle into a temporary staging
     * directory, then atomically promote the staging directory to
     * its content-addressed home.
     *
     * <p>The staging directory is created inside the slug directory
     * so that the final {@code Files.move} stays on the same
     * filesystem and can use
     * {@link StandardCopyOption#ATOMIC_MOVE}. If any file fetch
     * fails the staging directory is deleted and the error is
     * re-thrown so {@link #syncFragment} can clean up and record
     * {@link Outcome#FAILED}.
     *
     * @param remote   the manifest entry providing the slug and
     *                 content hash to fetch
     * @param finalDir the content-addressed destination path
     *                 ({@code <fragmentsRoot>/<slug>/<hash>})
     * @return a {@link Mono} that completes empty on success, or
     *         errors if any file fetch or the atomic promote fails
     */
    private Mono<Void> fetchBundle(
            final FederationManifest.Remote remote,
            final Path finalDir) {
        String slug = remote.slug();
        String hash = remote.contentHash();
        return federation.bundle(hash)
                // Resolve the file list from the bundle's
                // manifestJson.
                .map(this::fileList)
                .flatMap(files -> {
                    // Fall back to the single default entry file
                    // when manifestJson is empty.
                    List<String> toFetch = files.isEmpty()
                            ? List.of(DEFAULT_ENTRY_FILE) : files;
                    return ensureDir(fragmentsRoot().resolve(slug))
                            // Create the staging dir in the slug dir
                            // (same FS as finalDir).
                            .then(Mono.fromCallable(
                                    () -> Files.createTempDirectory(
                                            fragmentsRoot()
                                                    .resolve(slug),
                                            "." + hash + "-stage-"))
                                    .subscribeOn(
                                            Schedulers.boundedElastic()))
                            .flatMap(staging ->
                                    Flux.fromIterable(toFetch)
                                    // Fetch each file sequentially
                                    // into the staging dir.
                                    .concatMap(rel ->
                                            fetchFile(hash, rel,
                                                    staging))
                                    // All files landed — atomically
                                    // promote the staging dir.
                                    .then(promote(staging, finalDir))
                                    // Clean up staging dir on any
                                    // error, then re-throw.
                                    .onErrorResume(e ->
                                            removeQuietly(staging)
                                            .then(Mono.error(e))));
                })
                .doOnSuccess(v -> LOG.info("[sync] synced {} @ {}",
                        slug, shortHash(hash)));
    }

    /**
     * Stream one bundle file from federation to
     * {@code <staging>/<rel>}, writing it first to a {@code .part}
     * sibling and then atomically moving it into place.
     *
     * <p>A path-traversal guard rejects any {@code rel} whose
     * normalized form would escape the staging directory (e.g. a
     * {@code ../} sequence in the bundle metadata).
     *
     * <p>All filesystem operations are dispatched on
     * {@link Schedulers#boundedElastic()}.
     *
     * @param hash    the content hash identifying the bundle on the
     *                federation server
     * @param rel     relative path of the file within the bundle
     *                (e.g. {@code remoteEntry.json} or
     *                {@code chunks/main.js})
     * @param staging absolute path to the temporary staging
     *                directory for this bundle
     * @return a {@link Mono} that completes empty on success, or
     *         errors on a path-traversal attempt, a network error,
     *         or a filesystem error
     */
    private Mono<Void> fetchFile(
            final String hash,
            final String rel,
            final Path staging) {
        Path dest = staging.resolve(rel).normalize();
        // Path-traversal guard: reject any rel that escapes the
        // staging root.
        if (!dest.startsWith(staging)) {
            return Mono.error(new IllegalStateException(
                    "bundle file path escapes staging: " + rel));
        }
        // Write to a .part sibling first so a partial download is
        // never visible.
        Path part = dest.resolveSibling(dest.getFileName() + ".part");
        Flux<DataBuffer> body = federation.file(hash, rel);
        return ensureDir(dest.getParent())
                // Stream the DataBuffer flux directly to the .part
                // file (no heap buffering).
                .then(DataBufferUtils.write(body, part,
                        StandardOpenOption.CREATE,
                        StandardOpenOption.TRUNCATE_EXISTING,
                        StandardOpenOption.WRITE))
                // Atomically rename .part -> dest so the file
                // appears complete or not at all.
                .then(Mono.fromCallable(
                        () -> Files.move(part, dest,
                                StandardCopyOption.ATOMIC_MOVE))
                        .subscribeOn(Schedulers.boundedElastic()))
                .then();
    }

    /**
     * Make the fully-staged bundle directory world-readable (so
     * the nginx container running as uid 101 can read files written
     * by the sync container running as uid 1000 on the shared
     * emptyDir volume), then atomically move it to its
     * content-addressed home.
     *
     * <p>The move falls back from
     * {@link StandardCopyOption#ATOMIC_MOVE} to
     * {@link StandardCopyOption#REPLACE_EXISTING} on filesystems
     * where atomic cross-directory renames are unsupported (rare,
     * but guarded).
     *
     * <p>Dispatched entirely on {@link Schedulers#boundedElastic()}.
     *
     * @param staging  the fully-populated temporary staging
     *                 directory
     * @param finalDir the content-addressed destination
     *                 ({@code <fragmentsRoot>/<slug>/<hash>})
     * @return a {@link Mono} that completes empty on success, or
     *         errors on a filesystem failure
     */
    private Mono<Void> promote(
            final Path staging, final Path finalDir) {
        return Mono.fromCallable(() -> {
            // The nginx container reads this volume as a DIFFERENT
            // uid (101) than the sync container (1000) on a shared
            // emptyDir, so make the tree world-readable/traversable
            // before promoting it.
            makeWorldReadable(staging);
            try {
                Files.move(staging, finalDir,
                        StandardCopyOption.ATOMIC_MOVE);
            } catch (IOException atomicFailed) {
                // ATOMIC_MOVE can be unsupported across some FS
                // edge cases; fall back.
                Files.move(staging, finalDir,
                        StandardCopyOption.REPLACE_EXISTING);
            }
            // The staging dir was created 0700 by
            // createTempDirectory; ensure the promoted bundle dir
            // itself is traversable by the different-uid nginx.
            makeTraversable(finalDir);
            return finalDir;
        }).subscribeOn(Schedulers.boundedElastic()).then();
    }

    /**
     * Recursively grant {@code a+rX} on every file and directory
     * under {@code dir} so a different-uid process (nginx uid 101)
     * can read and traverse the synced bundle tree written by the
     * sync uid (1000).
     *
     * <p>POSIX-only: silently no-ops on non-POSIX filesystems
     * (e.g. Windows dev boxes, or a test temp dir on a non-POSIX
     * JVM). All permission changes are best-effort — individual
     * failures are swallowed and never propagate.
     *
     * <p>Also calls {@link #makeTraversable(Path)} on the parent
     * slug directory so the newly promoted bundle dir is reachable
     * from the slug root.
     *
     * @param dir the root of the staged bundle directory tree to
     *            make world-readable
     */
    private static void makeWorldReadable(final Path dir) {
        // Non-POSIX filesystem (e.g. Windows): skip.
        if (!dir.getFileSystem()
                .supportedFileAttributeViews().contains("posix")) {
            return;
        }
        try (Stream<Path> walk = Files.walk(dir)) {
            walk.forEach(p -> {
                try {
                    var perms = new HashSet<>(
                            Files.getPosixFilePermissions(p));
                    // Grant read to group and others on all entries.
                    perms.add(PosixFilePermission.OTHERS_READ);
                    perms.add(PosixFilePermission.GROUP_READ);
                    if (Files.isDirectory(p)) {
                        // Grant execute (traverse) to group and
                        // others on directories.
                        perms.add(PosixFilePermission.OTHERS_EXECUTE);
                        perms.add(PosixFilePermission.GROUP_EXECUTE);
                    }
                    Files.setPosixFilePermissions(p, perms);
                } catch (IOException ignored) {
                    /* best-effort */
                }
            });
        } catch (IOException ignored) {
            /* best-effort */
        }
        // Also make the parent slug dir traversable so nginx can
        // descend into it.
        makeTraversable(dir.getParent());
    }

    /**
     * Grant {@code a+r} on a single file so a different-uid reader
     * can read it. Best-effort and POSIX-only; silently no-ops on
     * non-POSIX filesystems or on a {@code null} path.
     *
     * @param file the file to make world-readable; {@code null} is
     *             silently ignored
     */
    private static void makeReadable(final Path file) {
        if (file == null || !file.getFileSystem()
                .supportedFileAttributeViews().contains("posix")) {
            return;
        }
        try {
            var perms = new HashSet<>(
                    Files.getPosixFilePermissions(file));
            perms.add(PosixFilePermission.OTHERS_READ);
            perms.add(PosixFilePermission.GROUP_READ);
            Files.setPosixFilePermissions(file, perms);
        } catch (IOException ignored) {
            /* best-effort */
        }
    }

    /**
     * Grant {@code a+rx} on a single directory so a different-uid
     * process can list and traverse it. Best-effort and POSIX-only;
     * silently no-ops on non-POSIX filesystems, a {@code null}
     * path, or a path that is not a directory.
     *
     * @param dir the directory to make world-traversable;
     *            {@code null} and non-directory paths are silently
     *            ignored
     */
    private static void makeTraversable(final Path dir) {
        if (dir == null || !Files.isDirectory(dir)
                || !dir.getFileSystem()
                        .supportedFileAttributeViews()
                        .contains("posix")) {
            return;
        }
        try {
            var perms = new HashSet<>(
                    Files.getPosixFilePermissions(dir));
            perms.add(PosixFilePermission.OTHERS_READ);
            perms.add(PosixFilePermission.OTHERS_EXECUTE);
            perms.add(PosixFilePermission.GROUP_READ);
            perms.add(PosixFilePermission.GROUP_EXECUTE);
            Files.setPosixFilePermissions(dir, perms);
        } catch (IOException ignored) {
            /* best-effort */
        }
    }

    /**
     * Delete every {@code <slug>/<hash>} directory on disk whose
     * composite key is NOT in {@code keep}, and remove any slug
     * directory that is left empty after its hash dirs are pruned.
     * Also sweeps leftover staging directories (names starting with
     * {@code .}).
     *
     * <p>Dispatched on {@link Schedulers#boundedElastic()}.
     *
     * @param keep the set of {@code "slug hash"} keys that must be
     *             preserved (built from the current manifest)
     * @return a {@link Mono} emitting the number of hash
     *         directories pruned
     */
    private Mono<Integer> prune(final Set<String> keep) {
        return Mono.fromCallable(() -> {
            Path fragRoot = fragmentsRoot();
            if (!Files.isDirectory(fragRoot)) {
                return 0;
            }
            int pruned = 0;
            try (Stream<Path> slugDirs = Files.list(fragRoot)) {
                List<Path> slugs =
                        slugDirs.filter(Files::isDirectory).toList();
                for (Path slugDir : slugs) {
                    String slug = slugDir.getFileName().toString();
                    try (Stream<Path> hashDirs =
                            Files.list(slugDir)) {
                        List<Path> hashes = hashDirs.toList();
                        for (Path hashDir : hashes) {
                            String name =
                                    hashDir.getFileName().toString();
                            // Sweep leftover staging dirs
                            // (.<hash>-stage-...) — best-effort.
                            if (name.startsWith(".")) {
                                deleteRecursive(hashDir);
                                continue;
                            }
                            // Prune any hash dir not present in the
                            // current manifest.
                            if (!keep.contains(key(slug, name))) {
                                deleteRecursive(hashDir);
                                pruned++;
                                LOG.info("[sync] pruned {} @ {}"
                                        + " (no longer in manifest)",
                                        slug, shortHash(name));
                            }
                        }
                    }
                    // Remove the slug dir itself if it is now empty.
                    try (Stream<Path> remaining =
                            Files.list(slugDir)) {
                        if (remaining.findAny().isEmpty()) {
                            Files.delete(slugDir);
                        }
                    }
                }
            }
            return pruned;
        }).subscribeOn(Schedulers.boundedElastic());
    }

    /**
     * Write (or overwrite) {@code /_fragments/index.json} with a
     * snapshot of the current fragment set, mapping each slug to
     * its bundle descriptor so the Angular host's
     * {@code loadRemoteModule} can discover the static serve paths.
     *
     * <p>The write is atomic: the JSON is first written to a
     * {@code .index.json.part} temp file, then renamed into place.
     * This ensures nginx never serves a partial index during the
     * write.
     *
     * <p>Dispatched on {@link Schedulers#boundedElastic()}.
     *
     * @param fragments the list of fragment remotes from the
     *                  current manifest; each produces one entry in
     *                  the index keyed by slug
     * @return a {@link Mono} that completes empty on success, or
     *         errors on a serialization or filesystem failure
     */
    private Mono<Void> writeIndex(
            final List<FederationManifest.Remote> fragments) {
        return Mono.fromCallable(() -> {
            // Build the slug -> descriptor map in manifest order.
            Map<String, Object> index = new LinkedHashMap<>();
            for (FederationManifest.Remote r : fragments) {
                if (r.slug() == null || r.slug().isBlank()) {
                    // The index is keyed BY slug, and Jackson refuses
                    // to serialise a null map key ("Null key for a
                    // Map not allowed in JSON"). That exception
                    // escaped writeIndex and aborted the WHOLE run,
                    // so one malformed manifest row took down the
                    // entire sync — every healthy fragment included.
                    // Such a row is already tallied as FAILED in
                    // syncFragment and cannot be addressed by a host
                    // anyway, so it simply does not belong in the
                    // index.
                    continue;
                }
                Map<String, Object> entry = new LinkedHashMap<>();
                entry.put("contentHash", r.contentHash());
                entry.put("version", r.version());
                entry.put("remoteEntryUrl", r.remoteEntryUrl());
                entry.put("exposedModule", r.exposedModule());
                entry.put("displayName", r.displayName());
                entry.put("visibility", r.visibility());
                // The static, content-addressed serve path this
                // fleet exposes.
                entry.put("servePath", "/" + FRAGMENTS_DIR
                        + "/" + r.slug()
                        + "/" + r.contentHash() + "/");
                index.put(r.slug(), entry);
            }
            Map<String, Object> doc = new LinkedHashMap<>();
            doc.put("generatedAt",
                    Instant.now().toString());
            doc.put("fragments", index);
            byte[] bytes = mapper.writerWithDefaultPrettyPrinter()
                    .writeValueAsBytes(doc);
            Files.createDirectories(fragmentsRoot());
            // Write to a .part file first so the index is never
            // partially visible.
            Path tmp = fragmentsRoot()
                    .resolve("." + INDEX_FILE + ".part");
            Files.write(tmp, bytes,
                    StandardOpenOption.CREATE,
                    StandardOpenOption.TRUNCATE_EXISTING);
            Path target = fragmentsRoot().resolve(INDEX_FILE);
            try {
                Files.move(tmp, target,
                        StandardCopyOption.ATOMIC_MOVE);
            } catch (IOException atomicFailed) {
                // Fall back if the FS does not support atomic
                // rename.
                Files.move(tmp, target,
                        StandardCopyOption.REPLACE_EXISTING);
            }
            // Make the index readable by the different-uid nginx,
            // and ensure all ancestor directories up to the nginx
            // root are traversable.
            makeReadable(target);
            makeTraversable(fragmentsRoot());
            makeTraversable(root);
            return null;
        }).subscribeOn(Schedulers.boundedElastic()).then();
    }

    // ── manifestJson file-list parsing ─────────────────────────

    /**
     * Parse the per-bundle file list from
     * {@link BundleMetadata#manifestJson()}.
     *
     * <p>Handles three common shapes of the {@code manifestJson}
     * field:
     * <ol>
     *   <li>A JSON array of strings:
     *       {@code ["remoteEntry.json","chunks/main.js"]}</li>
     *   <li>A JSON array of objects with a {@code path},
     *       {@code name}, or {@code file} string field (games /
     *       webpack stats format)</li>
     *   <li>A JSON object with a {@code files} key whose value is
     *       either of the above array shapes</li>
     * </ol>
     *
     * <p>An absent, blank, or {@code "{}"} value (today's
     * single-file federation reality) yields an EMPTY list. The
     * caller then falls back to fetching only
     * {@link #DEFAULT_ENTRY_FILE}. Unparseable JSON also yields an
     * empty list (the parse warning is logged).
     *
     * @param meta the bundle metadata record whose
     *             {@code manifestJson} field to parse; {@code null}
     *             is treated as an absent field and yields an empty
     *             list
     * @return the ordered list of relative file paths within the
     *         bundle; never {@code null}, but may be empty to
     *         signal fall-back to the default entry file
     */
    public List<String> fileList(final BundleMetadata meta) {
        String json = meta == null ? null : meta.manifestJson();
        // Empty / absent manifestJson means fall back to the single
        // default entry file.
        if (json == null || json.isBlank()
                || "{}".equals(json.trim())) {
            return List.of();
        }
        try {
            JsonNode jsonRoot = mapper.readTree(json);
            // Accept either a top-level array or an object with a
            // "files" array.
            JsonNode arr = jsonRoot.isArray()
                    ? jsonRoot : jsonRoot.path("files");
            if (!arr.isArray()) {
                return List.of();
            }
            List<String> files = new ArrayList<>();
            for (JsonNode n : arr) {
                String p = null;
                if (n.isTextual()) {
                    // Shape 1: array of plain strings.
                    p = n.asText();
                } else if (n.isObject()) {
                    // Shape 2: array of objects — try common field
                    // names in order.
                    for (String f
                            : new String[]{"path", "name", "file"}) {
                        if (n.hasNonNull(f)) {
                            p = n.get(f).asText();
                            break;
                        }
                    }
                }
                // Strip a leading slash so paths are always
                // relative.
                if (p != null && !p.isBlank()) {
                    files.add(p.startsWith("/")
                            ? p.substring(1) : p);
                }
            }
            return files;
        } catch (Exception e) {
            LOG.warn("[sync] manifestJson parse failed for {}"
                    + " (falling back to single entry): {}",
                    meta.contentHash(), e.toString());
            return List.of();
        }
    }

    // ── filesystem helpers (all on boundedElastic) ─────────────

    /**
     * Create {@code dir} and all missing ancestors. Dispatched on
     * {@link Schedulers#boundedElastic()}.
     *
     * @param dir the directory path to create
     * @return a {@link Mono} that completes empty on success
     */
    private Mono<Void> ensureDir(final Path dir) {
        return Mono.fromCallable(() -> Files.createDirectories(dir))
                .subscribeOn(Schedulers.boundedElastic()).then();
    }

    /**
     * Check whether {@code dir} exists as a directory. Dispatched
     * on {@link Schedulers#boundedElastic()}.
     *
     * @param dir the path to check
     * @return a {@link Mono} emitting {@code true} if {@code dir}
     *         is an existing directory, {@code false} otherwise
     */
    private Mono<Boolean> dirExists(final Path dir) {
        return Mono.fromCallable(() -> Files.isDirectory(dir))
                .subscribeOn(Schedulers.boundedElastic());
    }

    /**
     * Delete {@code dir} recursively and silently (errors are
     * swallowed). Used for best-effort cleanup of half-written
     * staging or bundle directories.
     *
     * @param dir the directory to delete; {@code null} and
     *            non-existent paths are silently ignored
     * @return a {@link Mono} that always completes empty
     */
    private Mono<Void> removeQuietly(final Path dir) {
        return Mono.fromCallable(() -> {
                    deleteRecursive(dir);
                    return null;
                })
                .subscribeOn(Schedulers.boundedElastic())
                .onErrorResume(e -> Mono.empty())
                .then();
    }

    /**
     * Recursively delete all files and directories under
     * {@code dir} (deepest first), then delete {@code dir} itself.
     * Silently skips individual entries that cannot be deleted.
     *
     * @param dir the root path to delete; {@code null} or
     *            non-existent paths are silently ignored
     * @throws IOException if the directory walk itself fails
     */
    private static void deleteRecursive(final Path dir)
            throws IOException {
        if (dir == null || !Files.exists(dir)) {
            return;
        }
        // Sort in reverse order so leaf entries are deleted before
        // their parents.
        try (Stream<Path> walk = Files.walk(dir)) {
            walk.sorted(Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (IOException ignored) {
                    /* best-effort */
                }
            });
        }
    }

    /**
     * Returns the {@code _fragments} sub-directory under the nginx
     * root.
     *
     * @return {@code <root>/_fragments}
     */
    private Path fragmentsRoot() {
        return root.resolve(FRAGMENTS_DIR);
    }

    /**
     * Returns the content-addressed bundle directory for a given
     * slug and hash.
     *
     * @param slug the remote slug (e.g. {@code admin-ui})
     * @param hash the SHA-256 content hash
     * @return {@code <root>/_fragments/<slug>/<hash>}
     */
    private Path bundleDir(final String slug, final String hash) {
        return fragmentsRoot().resolve(slug).resolve(hash);
    }

    /**
     * Builds the composite prune key for a slug/hash pair.
     * Space-separated so it cannot collide with either component
     * alone (slugs and hashes never contain spaces).
     *
     * @param slug the remote slug
     * @param hash the content hash
     * @return a string suitable for use as a set key
     */
    private static String key(final String slug, final String hash) {
        return slug + " " + hash;
    }

    /**
     * Returns the first {@value #SHORT_HASH_LENGTH} characters of
     * a content hash for use in log messages.
     *
     * @param hash the full content hash; {@code null} returns
     *             {@code "?"}
     * @return an abbreviated hash string
     */
    private static String shortHash(final String hash) {
        return hash == null ? "?"
                : hash.substring(0,
                        Math.min(SHORT_HASH_LENGTH, hash.length()));
    }

    /**
     * Assembles a {@link SyncResult} from the fragment count,
     * per-bundle outcomes, and the prune count.
     *
     * @param fragmentCount total number of fragment remotes in the
     *                      current manifest
     * @param outcomes      per-bundle outcome list collected from
     *                      the {@code concatMap} pipeline
     * @param pruned        number of stale bundle directories
     *                      deleted during this run
     * @return the populated {@link SyncResult}
     */
    private static SyncResult tally(
            final int fragmentCount,
            final List<Outcome> outcomes,
            final int pruned) {
        // Single pass over outcomes: group by identity and count
        // each bucket.
        var counts = outcomes.stream()
                .collect(Collectors.groupingBy(
                        o -> o, Collectors.counting()));
        int synced  = counts.getOrDefault(
                Outcome.SYNCED,  0L).intValue();
        int deduped = counts.getOrDefault(
                Outcome.DEDUPED, 0L).intValue();
        int failed  = counts.getOrDefault(
                Outcome.FAILED,  0L).intValue();
        int skipped = counts.getOrDefault(
                Outcome.SKIPPED, 0L).intValue();
        return new SyncResult(
                fragmentCount, synced, deduped, failed,
                skipped, pruned);
    }

    /**
     * Per-bundle sync outcome, used to tally the
     * {@link SyncResult} at the end of each run.
     */
    enum Outcome {
        /** The bundle was newly fetched and written to disk. */
        SYNCED,
        /**
         * The content-addressed bundle directory already existed;
         * no fetch needed.
         */
        DEDUPED,
        /** The bundle fetch or write failed; logged and skipped. */
        FAILED,
        /**
         * The module is registered but carries no content hash yet,
         * so there is no bundle to fetch. A normal registry state
         * (see {@code syncFragment}), NOT a failure.
         */
        SKIPPED
    }

    /**
     * Tally of one complete sync run.
     *
     * @param fragments total number of fragment remotes present in
     *                  the manifest
     * @param synced    number of bundles newly fetched and written
     *                  to disk
     * @param deduped   number of bundles skipped because the
     *                  content-addressed directory was already
     *                  present on disk
     * @param failed    number of bundles that failed (logged and
     *                  skipped)
     * @param skipped   number of modules registered without a
     *                  content hash yet, so there was no bundle to
     *                  fetch — a normal state, not a failure
     * @param pruned    number of stale bundle directories deleted
     *                  from disk
     */
    public record SyncResult(
            int fragments,
            int synced,
            int deduped,
            int failed,
            int skipped,
            int pruned) { }
}
