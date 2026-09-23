package net.hugmesh.fragment.federation;

import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import net.hugmesh.fragment.casdoor.CasdoorTokenClient;

/**
 * Reactive read client for the hugmesh-federation-api
 * bundle-sync contract:
 *
 * <ul>
 *   <li>{@link #manifest()} —
 *       {@code GET /api/federation/manifest}</li>
 *   <li>{@link #bundle(String)} —
 *       {@code GET /api/federation/bundles/{contentHash}}</li>
 *   <li>{@link #file(String, String)} —
 *       {@code GET /api/federation/bundles/{hash}/files/{*path}}
 *       streamed as a {@link DataBuffer} flux (no full-buffering,
 *       so a large bundle file never sits in heap).</li>
 * </ul>
 *
 * <p>Every call carries
 * {@code Authorization: Bearer <casdoor-jwt>} — the token is
 * minted/cached by {@link CasdoorTokenClient}. The bearer is
 * injected per-request (not a static default header) so a
 * re-minted token is always used.
 *
 * @see CasdoorTokenClient
 * @see FederationManifest
 * @see BundleMetadata
 */
public class FederationSyncClient {

    /** HTTP 401 Unauthorized status code. */
    private static final int HTTP_UNAUTHORIZED = 401;

    /** HTTP 403 Forbidden status code. */
    private static final int HTTP_FORBIDDEN = 403;

    /** Reactive HTTP client rooted at the federation base URL. */
    private final WebClient http;

    /** Token client that supplies a cached Casdoor JWT. */
    private final CasdoorTokenClient tokens;

    /**
     * Production constructor — builds a {@link WebClient} rooted
     * at {@code federationBaseUrl} so subsequent request URIs are
     * relative paths.
     *
     * @param federationBaseUrl base URL of the
     *                          hugmesh-federation-api service
     * @param tokenClient       the token client that supplies a
     *                          cached Casdoor JWT for each request
     */
    public FederationSyncClient(
            final String federationBaseUrl,
            final CasdoorTokenClient tokenClient) {
        this(WebClient.builder()
                .baseUrl(federationBaseUrl).build(), tokenClient);
    }

    /**
     * Test-only constructor — inject a pre-built {@link WebClient} (e.g.
     * backed by a stub exchange).
     *
     * @param webClient   pre-configured WebClient; request URIs in
     *                    this class are relative paths, so the
     *                    client must have a base URL set or the
     *                    URIs must be absolute
     * @param tokenClient the token client that supplies a cached
     *                    Casdoor JWT for each request
     */
    public FederationSyncClient(
            final WebClient webClient,
            final CasdoorTokenClient tokenClient) {
        this.http = webClient;
        this.tokens = tokenClient;
    }

    /**
     * Fetch the enabled+visible federation manifest.
     *
     * <p>The federation server applies the kill-switch
     * (disabled/circuit-open rows excluded) and visibility
     * filtering (caller sees only what its service JWT's
     * authorities allow) before returning this response, so all
     * remotes in the returned manifest are ones this fleet is
     * authorised to serve.
     *
     * @return a {@link Mono} emitting the deserialized
     *         {@link FederationManifest}, or an error if the token
     *         mint fails or the federation server returns a non-2xx
     *         status
     * @throws org.springframework.web.reactive.function.client
     *         .WebClientResponseException on a 4xx/5xx response
     *         from the federation server
     */
    public Mono<FederationManifest> manifest() {
        // Mint (or reuse) the service JWT, then issue the manifest GET.
        return tokens.token().flatMap(tok -> http.get()
                .uri("/api/federation/manifest")
                .headers(h -> h.setBearerAuth(tok))
                .retrieve()
                .bodyToMono(FederationManifest.class))
                .doOnError(
                        WebClientResponseException.class,
                        this::invalidateOnAuthError);
    }

    /**
     * Fetch the bundle metadata (including the
     * {@code manifestJson} file list) for a specific
     * content-addressed bundle.
     *
     * @param contentHash the SHA-256 content hash of the bundle
     *                    to look up
     * @return a {@link Mono} emitting the deserialized
     *         {@link BundleMetadata}, or an error if the content
     *         hash is unknown or the server returns a non-2xx status
     * @throws org.springframework.web.reactive.function.client
     *         .WebClientResponseException on a 4xx/5xx response
     *         from the federation server
     */
    public Mono<BundleMetadata> bundle(final String contentHash) {
        // Mint (or reuse) the service JWT, then fetch bundle metadata.
        return tokens.token().flatMap(tok -> http.get()
                .uri("/api/federation/bundles/{contentHash}",
                        contentHash)
                .headers(h -> h.setBearerAuth(tok))
                .retrieve()
                .bodyToMono(BundleMetadata.class))
                .doOnError(
                        WebClientResponseException.class,
                        this::invalidateOnAuthError);
    }

    /**
     * Stream one file from a content-addressed bundle as a sequence
     * of {@link DataBuffer} chunks. The file content is never fully
     * buffered in heap — each chunk is written to disk as it arrives.
     *
     * <p>{@code relativePath} is the path inside the bundle (e.g.
     * {@code remoteEntry.json} or {@code chunks/main.js}); it is
     * appended verbatim under {@code /files/}.
     *
     * <p>A nested {@code rel} ({@code chunks/main.js}) MUST keep
     * its {@code /} as a literal path separator: federation maps
     * the file route as {@code {*path}} (PathPattern wildcard) and
     * WebFlux does not decode an encoded slash ({@code %2F}) back
     * into a separator, so expanding {@code rel} as a single
     * {@code {var}} would make the server see
     * {@code chunks%2Fmain.js} and 404. So only
     * {@code contentHash} is templated; the slash-bearing
     * {@code rel} is appended to the path literally.
     *
     * @param contentHash  the SHA-256 content hash identifying the
     *                     bundle
     * @param relativePath path of the file within the bundle; a
     *                     leading {@code /} is stripped automatically
     * @return a {@link Flux} of {@link DataBuffer} chunks for the
     *         requested file, or an error if the file does not exist
     *         or the server returns a non-2xx status; callers must
     *         release each buffer after consumption
     */
    public Flux<DataBuffer> file(
            final String contentHash,
            final String relativePath) {
        // Strip any leading slash so the path segment is clean.
        String rel = relativePath.startsWith("/")
                ? relativePath.substring(1) : relativePath;
        // Template only the content hash; append rel literally to
        // preserve slashes so federation's {*path} pattern works.
        return tokens.token().flatMapMany(tok -> http.get()
                .uri(uriBuilder -> uriBuilder
                        .path("/api/federation/bundles/"
                                + "{contentHash}/files/")
                        .path(rel)
                        .build(contentHash))
                .headers(h -> h.setBearerAuth(tok))
                .retrieve()
                .bodyToFlux(DataBuffer.class))
                .doOnError(
                        WebClientResponseException.class,
                        this::invalidateOnAuthError);
    }

    /**
     * Invalidates the cached token when the federation endpoint
     * responds with 401 (Unauthorized) or 403 (Forbidden), forcing
     * a re-mint on the next request.
     * Called automatically by the {@code doOnError} hooks on each
     * federation call.
     *
     * @param ex the WebClient response exception carrying the status
     */
    private void invalidateOnAuthError(
            final WebClientResponseException ex) {
        int status = ex.getStatusCode().value();
        if (status == HTTP_UNAUTHORIZED || status == HTTP_FORBIDDEN) {
            tokens.invalidate();
        }
    }
}
