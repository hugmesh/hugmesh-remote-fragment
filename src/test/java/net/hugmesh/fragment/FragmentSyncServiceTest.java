package net.hugmesh.fragment;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.test.StepVerifier;
import net.hugmesh.fragment.casdoor.CasdoorTokenClient;
import net.hugmesh.fragment.federation.BundleMetadata;
import net.hugmesh.fragment.federation.FederationSyncClient;
import net.hugmesh.fragment.sync.FragmentSyncService;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Drives the REAL {@link FragmentSyncService} against a {@link FederationStub}
 * (recording ExchangeFunction) and a temp filesystem. Asserts the core
 * behaviours: kind filter, content-addressed write, dedupe (no re-fetch), prune of
 * a vanished fragment, single-bad-bundle resilience, multi-file manifestJson, and
 * the index.json.
 */
class FragmentSyncServiceTest {

    private final ObjectMapper mapper = new ObjectMapper();

    private FragmentSyncService serviceFor(FederationStub stub, Path root) {
        WebClient web = WebClient.builder().exchangeFunction(stub).baseUrl("http://federation.test").build();
        CasdoorTokenClient tokens = new CasdoorTokenClient(web, "http://casdoor.test/access_token", "app-test", "secret");
        FederationSyncClient fed = new FederationSyncClient(web, tokens);
        return new FragmentSyncService(fed, root);
    }

    private static String manifest(String... remoteJson) {
        return "{\"generatedAt\":\"now\",\"remotes\":[" + String.join(",", remoteJson) + "]}";
    }

    private static String remote(String slug, String kind, String hash) {
        return "{\"slug\":\"" + slug + "\",\"displayName\":\"" + slug + "\",\"kind\":\"" + kind
                + "\",\"remoteEntryUrl\":\"/_fragments/" + slug + "/" + hash + "/remoteEntry.json\""
                + ",\"exposedModule\":\"./Card\",\"version\":\"1.0.0\",\"contentHash\":\"" + hash
                + "\",\"visibility\":\"public\",\"metadata\":\"{}\"}";
    }

    /** A manifest entry with contentHash JSON-null (the registry's
     *  "registered, bundle not yet synced" state). */
    private static String remoteNoHash(String slug) {
        return "{\"slug\":\"" + slug + "\",\"displayName\":\"" + slug + "\",\"kind\":\"fragment\""
                + ",\"remoteEntryUrl\":\"http://" + slug + ":8080/fragment\""
                + ",\"exposedModule\":null,\"version\":\"0.1.0\",\"contentHash\":null"
                + ",\"visibility\":\"public\",\"metadata\":\"{}\"}";
    }

    /** A manifest entry with no slug at all — a malformed row. */
    private static String remoteNoSlug(String hash) {
        return "{\"slug\":null,\"displayName\":\"nameless\",\"kind\":\"fragment\""
                + ",\"remoteEntryUrl\":\"/_fragments/x/" + hash + "/remoteEntry.json\""
                + ",\"exposedModule\":\"./X\",\"version\":\"1.0.0\",\"contentHash\":\"" + hash
                + "\",\"visibility\":\"public\",\"metadata\":\"{}\"}";
    }

    @Test
    void aModuleWithNoContentHashIsSkipped_notCountedAsAFailure(@TempDir Path root) {
        // THE REGRESSION. federation-api registers a module before its bundle
        // exists and documents contentHash=null as "registered, bundle not yet
        // synced" (changelog 015-vanity-profile-fragment.xml). Counting that as
        // FAILED made a healthy module report as a failure and WARN on every
        // 60s tick forever — observed on the local stack as a permanent
        // "2 fragment(s) [0 synced, 0 deduped, 2 failed]".
        FederationStub stub = new FederationStub();
        stub.manifestJson = manifest(remoteNoHash("remote-fragment"));

        StepVerifier.create(serviceFor(stub, root).syncOnce())
                .assertNext(r -> {
                    assertThat(r.fragments()).isEqualTo(1);
                    assertThat(r.skipped()).isEqualTo(1);
                    assertThat(r.failed()).isZero();
                    assertThat(r.synced()).isZero();
                })
                .verifyComplete();
    }

    @Test
    void aMalformedEntryWithNoSlugIsStillAFailure(@TempDir Path root) {
        // The other half of the split: no slug means nothing can ever be done
        // with the row, so it must stay a loud failure rather than be quietly
        // absorbed into the new skipped bucket.
        FederationStub stub = new FederationStub();
        stub.manifestJson = manifest(remoteNoSlug("dddd4444"));

        StepVerifier.create(serviceFor(stub, root).syncOnce())
                .assertNext(r -> {
                    assertThat(r.failed()).isEqualTo(1);
                    assertThat(r.skipped()).isZero();
                })
                .verifyComplete();
    }

    @Test
    void syncsFragments_writesContentAddressedFiles_andSkipsNonFragments(@TempDir Path root) throws Exception {
        FederationStub stub = new FederationStub();
        String hashA = "aaaa1111bbbb2222";
        // a fragment + a NON-fragment (remote-game) that must be ignored.
        stub.manifestJson = manifest(
                remote("profile-card", "fragment", hashA),
                remote("snake", "remote-game", "ffff9999"));
        // single-file bundle (manifestJson "{}" => fallback to remoteEntry.json)
        stub.files.put(hashA + "/remoteEntry.json", "{\"name\":\"profile-card\"}");

        FragmentSyncService svc = serviceFor(stub, root);

        StepVerifier.create(svc.syncOnce())
                .assertNext(r -> {
                    assertThat(r.fragments()).isEqualTo(1);   // only the fragment
                    assertThat(r.synced()).isEqualTo(1);
                    assertThat(r.failed()).isZero();
                })
                .verifyComplete();

        Path entry = root.resolve("_fragments/profile-card/" + hashA + "/remoteEntry.json");
        assertThat(Files.exists(entry)).isTrue();
        assertThat(Files.readString(entry)).contains("profile-card");
        // non-fragment slug must NOT exist
        assertThat(Files.exists(root.resolve("_fragments/snake"))).isFalse();
        // index.json maps the slug to its content hash + serve path
        JsonNode idx = mapper.readTree(root.resolve("_fragments/index.json").toFile());
        assertThat(idx.path("fragments").path("profile-card").path("contentHash").asText()).isEqualTo(hashA);
        assertThat(idx.path("fragments").path("profile-card").path("servePath").asText())
                .isEqualTo("/_fragments/profile-card/" + hashA + "/");
    }

    @Test
    void dedupes_doesNotRefetchAnAlreadySyncedContentHash(@TempDir Path root) {
        FederationStub stub = new FederationStub();
        String hash = "deadbeefcafe0001";
        stub.manifestJson = manifest(remote("card", "fragment", hash));
        stub.files.put(hash + "/remoteEntry.json", "x");

        FragmentSyncService svc = serviceFor(stub, root);

        svc.syncOnce().block();   // first run: fetches
        int filesAfterFirst = stub.count("/remoteEntry.json");
        assertThat(filesAfterFirst).isEqualTo(1);

        var second = svc.syncOnce().block();   // second run: dedupes
        assertThat(second.deduped()).isEqualTo(1);
        assertThat(second.synced()).isZero();
        // No additional file fetch for the same content hash.
        assertThat(stub.count("/remoteEntry.json")).isEqualTo(filesAfterFirst);
    }

    @Test
    void prunes_aFragmentThatVanishedFromTheManifest(@TempDir Path root) {
        FederationStub stub = new FederationStub();
        String hashOld = "0000aaaa0000aaaa";
        String hashKeep = "1111bbbb1111bbbb";
        stub.manifestJson = manifest(
                remote("gone", "fragment", hashOld),
                remote("stays", "fragment", hashKeep));
        stub.files.put(hashOld + "/remoteEntry.json", "old");
        stub.files.put(hashKeep + "/remoteEntry.json", "keep");

        FragmentSyncService svc = serviceFor(stub, root);
        svc.syncOnce().block();
        assertThat(Files.exists(root.resolve("_fragments/gone/" + hashOld))).isTrue();

        // Next manifest drops 'gone' (kill-switch / disable / circuit-open).
        stub.manifestJson = manifest(remote("stays", "fragment", hashKeep));
        var r = svc.syncOnce().block();

        assertThat(r.pruned()).isEqualTo(1);
        assertThat(Files.exists(root.resolve("_fragments/gone"))).isFalse();       // slug dir emptied + removed
        assertThat(Files.exists(root.resolve("_fragments/stays/" + hashKeep))).isTrue();
    }

    @Test
    void survivesASingleBadBundle_onErrorResume_continuesWithTheGoodOne(@TempDir Path root) {
        FederationStub stub = new FederationStub();
        String good = "9999good9999good";
        String bad = "6666bad66666bad6";
        stub.manifestJson = manifest(
                remote("ok", "fragment", good),
                remote("broken", "fragment", bad));
        stub.files.put(good + "/remoteEntry.json", "ok");
        stub.failingBundles.add(bad);   // its /bundles/{hash} metadata call 500s

        FragmentSyncService svc = serviceFor(stub, root);
        var r = svc.syncOnce().block();

        assertThat(r.synced()).isEqualTo(1);
        assertThat(r.failed()).isEqualTo(1);
        assertThat(Files.exists(root.resolve("_fragments/ok/" + good + "/remoteEntry.json"))).isTrue();
        // the bad one left no partial dir behind
        assertThat(Files.exists(root.resolve("_fragments/broken/" + bad))).isFalse();
    }

    @Test
    void multiFileManifest_fetchesEveryListedFile(@TempDir Path root) {
        FederationStub stub = new FederationStub();
        String hash = "abcdef0123456789";
        stub.manifestJson = manifest(remote("multi", "fragment", hash));
        // bundle metadata lists multiple files (the games multi-file pattern)
        stub.bundleManifest.put(hash, "[\"remoteEntry.json\",\"chunks/main.js\",\"styles.css\"]");
        stub.files.put(hash + "/remoteEntry.json", "entry");
        stub.files.put(hash + "/chunks/main.js", "console.log(1)");
        stub.files.put(hash + "/styles.css", "body{}");

        FragmentSyncService svc = serviceFor(stub, root);
        var r = svc.syncOnce().block();

        assertThat(r.synced()).isEqualTo(1);
        Path base = root.resolve("_fragments/multi/" + hash);
        assertThat(Files.exists(base.resolve("remoteEntry.json"))).isTrue();
        assertThat(Files.exists(base.resolve("chunks/main.js"))).isTrue();   // nested path created
        assertThat(Files.exists(base.resolve("styles.css"))).isTrue();
    }

    @Test
    void fileList_parsesArray_objectFiles_andFallsBackOnEmpty() {
        FragmentSyncService svc = serviceFor(new FederationStub(), Path.of("/tmp/unused"));
        assertThat(svc.fileList(meta("[\"a.js\",\"b.css\"]"))).containsExactly("a.js", "b.css");
        assertThat(svc.fileList(meta("{\"files\":[{\"path\":\"x/y.js\"}]}"))).containsExactly("x/y.js");
        assertThat(svc.fileList(meta("{}"))).isEmpty();
        assertThat(svc.fileList(meta(""))).isEmpty();
        assertThat(svc.fileList(meta("not json"))).isEmpty();
    }

    private static BundleMetadata meta(String manifestJson) {
        return new BundleMetadata(
                1L, null, "x", null, "hash", manifestJson, "public", null, null);
    }
}
