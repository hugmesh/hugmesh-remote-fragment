package systems.hugmesh.fragment;

import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.reactive.function.client.ClientRequest;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.ExchangeFunction;
import reactor.core.publisher.Mono;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A recording {@link ExchangeFunction} that fakes the hugmesh-federation-api
 * for the sync tests — no live federation, no MockWebServer, no network. It
 * answers:
 * <ul>
 *   <li>{@code GET /api/federation/manifest} — the configured manifest JSON</li>
 *   <li>{@code GET /api/federation/bundles/{hash}} — bundle metadata (manifestJson)</li>
 *   <li>{@code GET /api/federation/bundles/{hash}/files/{*path}} — file bytes</li>
 *   <li>{@code POST .../access_token} — a fake Casdoor token mint</li>
 * </ul>
 * Per-path request counts are recorded so tests can assert dedupe (no re-fetch).
 */
public class FederationStub implements ExchangeFunction {

    public final List<String> requestedPaths = new ArrayList<>();
    public final Map<String, Integer> pathCounts = new LinkedHashMap<>();

    /** raw manifest body to return. */
    public String manifestJson = "{\"generatedAt\":\"now\",\"remotes\":[]}";
    /** contentHash -> bundle-metadata manifestJson value (the file list). */
    public final Map<String, String> bundleManifest = new LinkedHashMap<>();
    /** "hash/relpath" -> file body bytes. */
    public final Map<String, String> files = new LinkedHashMap<>();
    /** content-hashes whose metadata call should 500 (simulate a bad bundle). */
    public final List<String> failingBundles = new ArrayList<>();

    @Override
    public Mono<ClientResponse> exchange(ClientRequest request) {
        String path = request.url().getPath();
        requestedPaths.add(path);
        pathCounts.merge(path, 1, Integer::sum);

        if (path.endsWith("/access_token")) {
            return json("{\"access_token\":\"test.jwt.token\",\"token_type\":\"Bearer\",\"expires_in\":3600}");
        }
        if (path.endsWith("/api/federation/manifest")) {
            return json(manifestJson);
        }
        // /api/federation/bundles/{hash}/files/{*path}
        int filesIdx = path.indexOf("/files/");
        if (path.contains("/api/federation/bundles/") && filesIdx >= 0) {
            String hash = path.substring(path.indexOf("/bundles/") + "/bundles/".length(), filesIdx);
            String rel = path.substring(filesIdx + "/files/".length());
            String key = hash + "/" + rel;
            String body = files.get(key);
            if (body == null) return status(HttpStatus.NOT_FOUND, "no file: " + key);
            return raw(body);
        }
        // /api/federation/bundles/{hash}
        if (path.contains("/api/federation/bundles/")) {
            String hash = path.substring(path.indexOf("/bundles/") + "/bundles/".length());
            if (failingBundles.contains(hash)) {
                return status(HttpStatus.INTERNAL_SERVER_ERROR, "boom");
            }
            String mj = bundleManifest.getOrDefault(hash, "{}");
            String meta = "{\"id\":1,\"slug\":\"x\",\"contentHash\":\"" + hash
                    + "\",\"manifestJson\":" + jsonString(mj) + ",\"visibility\":\"public\"}";
            return json(meta);
        }
        return status(HttpStatus.NOT_FOUND, "unhandled: " + path);
    }

    public int count(String pathSuffix) {
        return (int) requestedPaths.stream().filter(p -> p.endsWith(pathSuffix)).count();
    }

    public boolean hit(String pathSuffix) {
        return requestedPaths.stream().anyMatch(p -> p.endsWith(pathSuffix));
    }

    private static Mono<ClientResponse> json(String body) {
        return Mono.just(ClientResponse.create(HttpStatus.OK)
                .header("Content-Type", MediaType.APPLICATION_JSON_VALUE)
                .body(body).build());
    }

    private static Mono<ClientResponse> raw(String body) {
        return Mono.just(ClientResponse.create(HttpStatus.OK)
                .header("Content-Type", MediaType.APPLICATION_OCTET_STREAM_VALUE)
                .body(body).build());
    }

    private static Mono<ClientResponse> status(HttpStatus s, String body) {
        return Mono.just(ClientResponse.create(s)
                .header("Content-Type", MediaType.TEXT_PLAIN_VALUE)
                .body(body).build());
    }

    /** Encode a raw JSON value as a JSON string literal (manifestJson is a String field). */
    private static String jsonString(String raw) {
        StringBuilder sb = new StringBuilder("\"");
        for (char c : raw.toCharArray()) {
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                default -> sb.append(c);
            }
        }
        return sb.append('"').toString();
    }
}
