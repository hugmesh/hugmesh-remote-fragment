/*
 * Boot smoke test for the GraalVM native binary produced by
 * `./mvnw -Pnative native:compile`.
 *
 * WHY THIS EXISTS: a native build that merely COMPILES proves almost nothing.
 * Every interesting GraalVM failure in a Spring application is a RUNTIME one --
 * a missing reflection/resource/serialization hint surfaces as a
 * ClassNotFoundException, a NoSuchMethodException, an UnsupportedFeatureError or
 * an empty deserialised DTO the first time the code path executes, long after
 * native-image exited 0. So CI runs the binary end to end against a stub
 * federation + stub Casdoor and asserts the bytes actually land on disk.
 *
 * MEASURED, not assumed. Two mutations of NativeHintsConfig were built and run:
 *   - drop the FederationManifest/.Remote registrations -> native-image BUILD
 *     SUCCESS, binary exits 1 with "Record components not available for record
 *     class net.hugmesh.fragment.federation.FederationManifest".
 *   - drop the BundleMetadata registration -> native-image BUILD SUCCESS, binary
 *     exits **0** having logged "1 failed" and written nothing. Only the on-disk
 *     assertions below catch that one -- which is exactly why this test asserts
 *     files and not just an exit status.
 *
 * Deliberately a single-file Java program run by the JDK source launcher
 * (`java scripts/NativeSmoke.java <binary>`): the GraalVM build image already
 * has a JDK, so this adds no python/curl/jq dependency to the CI job, and the
 * stub speaks exactly the wire shapes in src/test/java/.../FederationStub.java.
 *
 * TWO LEGS, because the pod runs the binary in both shapes:
 *
 *   1. SYNC_MODE=once -- the initContainer shape. Mint a Casdoor token, pull the
 *      manifest + bundle, write a multi-file bundle (including a nested path)
 *      and index.json, exit 0.
 *        covers: reactive WebClient POST w/ form body, Jackson-3 codec ->
 *                FederationManifest + .Remote records, BundleMetadata record,
 *                Jackson-2 readTree of the manifestJson STRING, nested directory
 *                creation, FragmentSyncRunner ONCE + System.exit.
 *
 *   2. SYNC_MODE=loop -- the long-running sidecar shape, which is what actually
 *      runs in production and which leg 1 never touches. Boot, let the first tick
 *      sync, then poll /actuator/health/readiness over HTTP until it flips UP.
 *        covers: Flux.interval/concatMap scheduling under Substrate, the Netty
 *                HTTP *server* (leg 1 only ever exercises the client), the
 *                actuator health endpoint + FirstSyncHealthIndicator and the
 *                serialisation of its detail map.
 *
 * Exit status: 0 = both legs passed. Anything else is a hard failure -- there is
 * no "skipped" path, by design.
 */
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class NativeSmoke {

    /** contentHash the stub federation advertises for the one fragment. */
    private static final String HASH = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";
    /** slug of the one fragment the stub advertises. */
    private static final String SLUG = "smoke-ui";
    /** files the stub bundle contains (multi-file, incl. a nested path). */
    private static final Map<String, String> FILES = Map.of(
            "remoteEntry.json", "{\"name\":\"smokeUi\",\"exposes\":[]}",
            "chunks/main.js", "export const smoke = 1;\n");

    /** How long the native binary gets to boot, sync and exit (leg 1). */
    private static final int TIMEOUT_SECONDS = 180;
    /** How long leg 2 waits for readiness to flip UP. */
    private static final int READY_TIMEOUT_SECONDS = 120;

    /** The port line Spring Boot logs once the reactive server is listening. */
    private static final Pattern PORT_LINE =
            Pattern.compile("Netty started on port (\\d+)");

    /** Paths the stub was asked for, so the test can assert real traffic. */
    private static final List<String> SEEN = new ArrayList<>();

    /** Base URL of the stub federation + Casdoor, set in main. */
    private static String stubBase;

    private NativeSmoke() {
    }

    public static void main(final String[] args) throws Exception {
        if (args.length < 1) {
            fail("usage: java scripts/NativeSmoke.java <path-to-native-binary>");
        }
        Path binary = Path.of(args[0]).toAbsolutePath();
        if (!Files.isExecutable(binary)) {
            fail("native binary not found or not executable: " + binary
                    + " (run: ./mvnw -Pnative -DskipTests native:compile)");
        }

        // A backlog and a real thread pool, NOT setExecutor(null). The default
        // single dispatcher thread is fine on an idle box and starves on a busy
        // CI runner: measured on this host at load average 98, the binary's
        // token POST came back "Connection prematurely closed BEFORE response"
        // and the gate went red for a reason that had nothing to do with the
        // native image. A flaky gate gets muted, and a muted gate is an inert
        // gate -- which is the failure this whole job exists to avoid.
        HttpServer stub = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 64);
        stub.createContext("/", NativeSmoke::route);
        ExecutorService stubPool = Executors.newFixedThreadPool(8, r -> {
            Thread t = new Thread(r, "smoke-stub");
            t.setDaemon(true);
            return t;
        });
        stub.setExecutor(stubPool);
        stub.start();
        stubBase = "http://127.0.0.1:" + stub.getAddress().getPort();
        System.out.println("[smoke] stub federation + casdoor on " + stubBase);

        try {
            legOnce(binary);
            legLoop(binary);
        } finally {
            stub.stop(0);
            stubPool.shutdownNow();
        }

        System.out.println("[smoke] OK -- native binary passed BOTH legs:"
                + " once-mode sync to disk, and loop-mode readiness over HTTP.");
    }

    // ---------------------------------------------------------------- leg 1

    /** initContainer shape: one full sync, bytes on disk, exit 0. */
    private static void legOnce(final Path binary) throws Exception {
        System.out.println("[smoke] --- leg 1/2: SYNC_MODE=once (initContainer shape)");
        resetSeen();
        Path root = Files.createTempDirectory("native-smoke-once-");
        Path out = root.resolve("run.log");

        ProcessBuilder pb = baseProcess(binary, out, root);
        pb.environment().put("SYNC_MODE", "once");

        System.out.println("[smoke] running " + binary + " (SYNC_MODE=once, target=" + root + ")");
        long started = System.nanoTime();
        Process p = pb.start();
        if (!p.waitFor(TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
            p.destroyForcibly();
            dump(out);
            fail("native binary did not exit within " + TIMEOUT_SECONDS + "s");
        }
        int status = p.exitValue();
        long ms = (System.nanoTime() - started) / 1_000_000L;
        System.out.println("[smoke] exited " + status + " after " + ms + " ms");

        if (status != 0) {
            dump(out);
            fail("native binary exited " + status + " (expected 0)");
        }

        // The binary really talked to the stub -- not a no-op run that exited 0
        // because nothing happened.
        requireSeen("/api/login/oauth/access_token", out);
        requireSeen("/api/federation/manifest", out);
        requireSeen("/api/federation/bundles/" + HASH, out);

        assertSynced(root, out);
        System.out.println("[smoke] leg 1 OK -- minted a token, synced " + FILES.size()
                + " file(s) for " + SLUG + " and exited 0.");
        deleteTree(root);
    }

    // ---------------------------------------------------------------- leg 2

    /**
     * Sidecar shape: boot in loop mode and poll the actuator readiness probe
     * until it flips UP. This is the leg that exercises the embedded Netty
     * SERVER, the actuator endpoint and FirstSyncHealthIndicator under native
     * image -- none of which leg 1 touches, because once-mode exits before
     * anything ever calls in over HTTP.
     */
    private static void legLoop(final Path binary) throws Exception {
        System.out.println("[smoke] --- leg 2/2: SYNC_MODE=loop (sidecar shape)");
        resetSeen();
        Path root = Files.createTempDirectory("native-smoke-loop-");
        Path out = root.resolve("run.log");

        ProcessBuilder pb = baseProcess(binary, out, root);
        Map<String, String> env = pb.environment();
        env.put("SYNC_MODE", "loop");
        env.put("SYNC_INTERVAL", "PT5S");
        // Force the health DETAILS into the response body so the readiness
        // payload really serialises FirstSyncHealthIndicator's counters rather
        // than collapsing to a bare {"status":"UP"} that would pass vacuously.
        env.put("MANAGEMENT_ENDPOINT_HEALTH_SHOW_DETAILS", "always");

        System.out.println("[smoke] running " + binary + " (SYNC_MODE=loop, target=" + root + ")");
        Process p = pb.start();
        try {
            int port = awaitPort(p, out);
            String probe = "http://127.0.0.1:" + port + "/actuator/health/readiness";
            System.out.println("[smoke] readiness probe: " + probe);

            HttpClient http = HttpClient.newBuilder()
                    .connectTimeout(Duration.ofSeconds(5))
                    .build();
            String body = null;
            int code = -1;
            long deadline = System.nanoTime()
                    + TimeUnit.SECONDS.toNanos(READY_TIMEOUT_SECONDS);
            while (System.nanoTime() < deadline) {
                if (!p.isAlive()) {
                    dump(out);
                    fail("native binary exited (" + p.exitValue()
                            + ") in loop mode before readiness ever flipped UP");
                }
                try {
                    HttpResponse<String> r = http.send(
                            HttpRequest.newBuilder(URI.create(probe))
                                    .timeout(Duration.ofSeconds(5))
                                    .GET().build(),
                            HttpResponse.BodyHandlers.ofString());
                    code = r.statusCode();
                    body = r.body();
                    // 503 = DOWN, which is CORRECT until the first sync lands.
                    if (code == 200) {
                        break;
                    }
                } catch (IOException | InterruptedException retry) {
                    // server not listening yet, or mid-shutdown: keep polling.
                    code = -1;
                }
                Thread.sleep(250L);
            }

            if (code != 200) {
                dump(out);
                fail("readiness never returned 200 within " + READY_TIMEOUT_SECONDS
                        + "s (last status " + code + ", body " + body + ")."
                        + " The actuator endpoint or the health indicator did not"
                        + " work under native image.");
            }
            System.out.println("[smoke] readiness 200: " + oneLine(body));

            if (body == null || !body.contains("\"UP\"")) {
                dump(out);
                fail("readiness returned 200 but the body is not UP: " + body);
            }
            // A bare {"status":"UP"} would mean readinessState alone answered and
            // FirstSyncHealthIndicator's details never serialised -- a green that
            // proves nothing. Require the indicator's own counters.
            for (String needle : List.of("firstSync", "fragments", "synced")) {
                if (!body.contains(needle)) {
                    dump(out);
                    fail("readiness body is missing \"" + needle + "\" -- the"
                            + " FirstSyncHealthIndicator details did not serialise"
                            + " under native image: " + body);
                }
            }

            // Readiness only flips UP after a real sync, so the bytes must be on
            // disk too -- loop mode writes through the same path as once mode.
            requireSeen("/api/federation/manifest", out);
            assertSynced(root, out);
        } finally {
            p.destroy();
            if (!p.waitFor(20, TimeUnit.SECONDS)) {
                p.destroyForcibly();
                p.waitFor(10, TimeUnit.SECONDS);
            }
        }
        System.out.println("[smoke] leg 2 OK -- looped, served /actuator/health/readiness"
                + " and reported UP with live sync counters.");
        deleteTree(root);
    }

    /**
     * Read the ephemeral port Spring Boot logged. SERVER_PORT=0 is deliberate --
     * picking a fixed port would collide with whatever else is on a shared CI
     * runner, and pre-binding one to "find a free port" races.
     */
    private static int awaitPort(final Process p, final Path out) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(60);
        while (System.nanoTime() < deadline) {
            if (Files.isRegularFile(out)) {
                Matcher m = PORT_LINE.matcher(Files.readString(out, StandardCharsets.UTF_8));
                if (m.find()) {
                    return Integer.parseInt(m.group(1));
                }
            }
            if (!p.isAlive()) {
                dump(out);
                fail("native binary exited (" + p.exitValue()
                        + ") in loop mode before the HTTP server started");
            }
            Thread.sleep(200L);
        }
        dump(out);
        fail("the native binary never logged a listening port within 60s");
        return -1;
    }

    // ------------------------------------------------------------- shared

    /** Common process shape for both legs. */
    private static ProcessBuilder baseProcess(final Path binary, final Path out,
            final Path root) {
        ProcessBuilder pb = new ProcessBuilder(binary.toString());
        pb.redirectErrorStream(true);
        pb.redirectOutput(out.toFile());
        Map<String, String> env = pb.environment();
        env.put("FEDERATION_BASE_URL", stubBase);
        env.put("CASDOOR_TOKEN_URL", stubBase + "/api/login/oauth/access_token");
        env.put("CASDOOR_CLIENT_ID", "app-fragment-sync");
        env.put("CASDOOR_CLIENT_SECRET", "smoke-secret");
        env.put("SYNC_TARGET_DIR", root.toString());
        // 0 = ephemeral: never collide with anything already on the runner.
        env.put("SERVER_PORT", "0");
        return pb;
    }

    /** Assert the synced tree and index.json really landed under {@code root}. */
    private static void assertSynced(final Path root, final Path out) throws IOException {
        Path fragments = root.resolve("_fragments");
        for (Map.Entry<String, String> f : FILES.entrySet()) {
            Path onDisk = fragments.resolve(SLUG).resolve(HASH).resolve(f.getKey());
            if (!Files.isRegularFile(onDisk)) {
                dump(out);
                fail("expected synced file missing: " + onDisk);
            }
            String body = Files.readString(onDisk, StandardCharsets.UTF_8);
            if (!body.equals(f.getValue())) {
                dump(out);
                fail("synced file content mismatch at " + onDisk
                        + "\n  expected: " + f.getValue() + "\n  actual:   " + body);
            }
        }

        Path index = fragments.resolve("index.json");
        if (!Files.isRegularFile(index)) {
            dump(out);
            fail("index.json was not written at " + index);
        }
        String indexJson = Files.readString(index, StandardCharsets.UTF_8);
        // The manifest DTO records only deserialise correctly if their native
        // reflection hints are in place; a missing hint yields null fields here.
        for (String needle : List.of(SLUG, HASH, "smokeUi.remoteEntry", "1.2.3")) {
            if (!indexJson.contains(needle)) {
                dump(out);
                fail("index.json is missing \"" + needle + "\" -- the manifest DTO did not"
                        + " deserialise under native image.\n" + indexJson);
            }
        }
        System.out.println("[smoke] index.json: " + oneLine(indexJson));
    }

    private static void route(final HttpExchange x) throws IOException {
        String path = x.getRequestURI().getPath();
        synchronized (SEEN) {
            SEEN.add(path);
        }
        drain(x.getRequestBody());
        if (path.endsWith("/access_token")) {
            send(x, 200, "application/json",
                    "{\"access_token\":\"smoke.jwt.token\",\"token_type\":\"Bearer\","
                            + "\"expires_in\":3600}");
            return;
        }
        if (path.endsWith("/api/federation/manifest")) {
            send(x, 200, "application/json", "{\"generatedAt\":\"2026-01-01T00:00:00Z\","
                    + "\"remotes\":[{\"slug\":\"" + SLUG + "\",\"displayName\":\"Smoke UI\","
                    + "\"kind\":\"fragment\",\"remoteEntryUrl\":\"https://smokeUi.remoteEntry/x\","
                    + "\"exposedModule\":\"./Module\",\"version\":\"1.2.3\",\"contentHash\":\""
                    + HASH + "\",\"visibility\":\"public\",\"metadata\":\"{}\","
                    + "\"unknownFutureField\":\"ignored\"}]}");
            return;
        }
        int filesIdx = path.indexOf("/files/");
        if (path.contains("/api/federation/bundles/") && filesIdx >= 0) {
            String rel = path.substring(filesIdx + "/files/".length());
            String body = FILES.get(rel);
            if (body == null) {
                send(x, 404, "text/plain", "no such file: " + rel);
                return;
            }
            send(x, 200, "application/octet-stream", body);
            return;
        }
        if (path.contains("/api/federation/bundles/")) {
            // manifestJson is a JSON *string* on the wire holding the file list.
            String fileList = "{\\\"files\\\":[\\\"remoteEntry.json\\\",\\\"chunks/main.js\\\"]}";
            send(x, 200, "application/json", "{\"id\":1,\"slug\":\"" + SLUG
                    + "\",\"contentHash\":\"" + HASH + "\",\"manifestJson\":\"" + fileList
                    + "\",\"visibility\":\"public\"}");
            return;
        }
        send(x, 404, "text/plain", "unhandled: " + path);
    }

    private static void resetSeen() {
        synchronized (SEEN) {
            SEEN.clear();
        }
    }

    private static void requireSeen(final String suffix, final Path out) {
        boolean hit;
        List<String> snapshot;
        synchronized (SEEN) {
            hit = SEEN.stream().anyMatch(p -> p.endsWith(suffix));
            snapshot = List.copyOf(SEEN);
        }
        if (!hit) {
            dump(out);
            fail("the native binary never requested " + suffix + " -- it exited 0 without"
                    + " doing any work. Stub saw: " + snapshot);
        }
    }

    private static void send(final HttpExchange x, final int code, final String type,
            final String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        x.getResponseHeaders().add("Content-Type", type);
        x.sendResponseHeaders(code, bytes.length);
        x.getResponseBody().write(bytes);
        x.close();
    }

    private static void drain(final InputStream in) throws IOException {
        try (in) {
            in.readAllBytes();
        }
    }

    private static String oneLine(final String s) {
        return s == null ? "(null)" : s.replaceAll("\\s+", " ").trim();
    }

    private static void dump(final Path log) {
        try {
            System.err.println("---- native binary output ----");
            System.err.println(Files.readString(log, StandardCharsets.UTF_8));
            System.err.println("------------------------------");
        } catch (IOException e) {
            System.err.println("(could not read " + log + ": " + e + ")");
        }
    }

    private static void deleteTree(final Path root) throws IOException {
        try (var walk = Files.walk(root)) {
            walk.sorted((a, b) -> b.getNameCount() - a.getNameCount())
                    .forEach(p -> {
                        try {
                            Files.deleteIfExists(p);
                        } catch (IOException ignored) {
                            // best effort: a leftover temp dir must never fail the gate
                        }
                    });
        }
    }

    private static void fail(final String message) {
        System.err.println("[smoke] FAIL: " + message);
        System.exit(1);
    }
}
