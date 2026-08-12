package net.hugmesh.fragment.casdoor;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.MediaType;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.web.reactive.function.BodyInserters;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Mints (and caches) a Casdoor SERVICE JWT via the OAuth2
 * client-credentials grant — the bearer the federation resource
 * server requires on every manifest/bundles call.
 *
 * <p>This is NOT the HTTP-Basic admin-API pattern the platform's
 * {@code CasdoorAdminClient} uses (that hits
 * {@code /api/get-role} etc. and is NOT a bearer token). The
 * federation fetch path is an OAuth2 resource server
 * ({@code .authenticated()}), so the sync must present a real
 * Casdoor-signed JWT. The canonical mint is the hugmesh.games
 * BFF flow:
 *
 * <pre>
 *   POST {tokenUrl}
 *       (= {casdoorBase}/api/login/oauth/access_token)
 *   Content-Type: application/x-www-form-urlencoded
 *   grant_type=client_credentials
 *       &amp;client_id={id}&amp;client_secret={secret}
 *   -&gt; 200 { "access_token": "&lt;RS256 JWT&gt;",
 *             "token_type":"Bearer", "expires_in":... }
 * </pre>
 *
 * <p>The token is cached in an {@link AtomicReference} and
 * re-minted shortly before {@code expires_in}; callers can also
 * force a re-mint via {@link #invalidate()} on a 401. A service
 * JWT with the {@code ROLE_USER} floor is enough for the manifest
 * read + bundle fetch (both only require
 * {@code authenticated()}); it sees only {@code public} fragments
 * unless its Casdoor app/user carries ROLE_ADMIN/ROLE_INTERNAL
 * (see README).
 *
 * @see FederationSyncClient
 */
public class CasdoorTokenClient {

    /** Logger for this class. */
    private static final Logger LOG =
            LoggerFactory.getLogger(CasdoorTokenClient.class);

    /**
     * Clock skew applied when deciding whether the cached token is
     * still fresh: the token is treated as stale this long before
     * its stated {@code expires_in} so a federation call never
     * races the clock boundary.
     */
    private static final Duration EXPIRY_SKEW = Duration.ofSeconds(60);

    /**
     * Fallback cache lifetime assumed when Casdoor omits
     * {@code expires_in} from the token response (should not happen
     * in normal deployments, but guards against a misconfigured or
     * older Casdoor version).
     */
    private static final Duration DEFAULT_TTL = Duration.ofMinutes(30);

    /**
     * Target type for decoding the Casdoor token JSON response as a
     * generic object map. The reactive WebFlux JSON codec (Jackson 3
     * under Boot 4) binds the
     * {@code {"access_token":...,"expires_in":...}} body to this map
     * so the two fields can be read without binding to a DTO and
     * independently of the Jackson major version.
     */
    private static final ParameterizedTypeReference<Map<String, Object>>
            JSON_OBJECT = new ParameterizedTypeReference<>() { };

    /** Reactive HTTP client used to POST to the token endpoint. */
    private final WebClient http;

    /** Absolute URL of the Casdoor client-credentials endpoint. */
    private final String tokenPath;

    /** Casdoor OAuth app ID for this machine account. */
    private final String clientId;

    /** Casdoor OAuth app secret for this machine account. */
    private final String clientSecret;

    /** Cached token + the instant it should be considered stale. */
    private final AtomicReference<Cached> cache =
            new AtomicReference<>(null);

    /**
     * Production constructor — builds its own default
     * {@link WebClient}.
     *
     * @param tokenUrl        absolute URL of the Casdoor token
     *                        endpoint (e.g. the cluster-local
     *                        Casdoor
     *                        {@code /api/login/oauth/access_token})
     * @param oauthClientId   Casdoor OAuth app ID of the machine
     *                        account (e.g.
     *                        {@code app-fragment-sync})
     * @param oauthClientSecret Casdoor OAuth app secret, injected
     *                        from the
     *                        {@code fragment-sync-casdoor} k8s
     *                        Secret
     */
    public CasdoorTokenClient(
            final String tokenUrl,
            final String oauthClientId,
            final String oauthClientSecret) {
        this(WebClient.builder().build(),
                tokenUrl, oauthClientId, oauthClientSecret);
    }

    /**
     * Test seam — inject a pre-built {@link WebClient} (e.g. backed
     * by a stub exchange). {@code tokenUrl} is used as the absolute
     * request URI.
     *
     * @param webClient         pre-configured WebClient;
     *                          {@code tokenUrl} must be an absolute
     *                          URI because no base URL is set
     * @param tokenUrl          absolute URL of the Casdoor token
     *                          endpoint
     * @param oauthClientId     Casdoor OAuth app ID
     * @param oauthClientSecret Casdoor OAuth app secret
     */
    public CasdoorTokenClient(
            final WebClient webClient,
            final String tokenUrl,
            final String oauthClientId,
            final String oauthClientSecret) {
        this.http = webClient;
        this.tokenPath = tokenUrl;
        this.clientId = oauthClientId;
        this.clientSecret = oauthClientSecret;
    }

    /**
     * Return a valid bearer token, minting (or re-minting) only
     * when the cached token is absent or stale.
     *
     * <p>Safe to call concurrently: the hot-path reads from an
     * {@link AtomicReference} without locking; a re-mint race can
     * result in two parallel mint calls but both will succeed and
     * only one value will win the {@code cache.set()}.
     *
     * @return a {@link Mono} that emits the bearer token string, or
     *         errors if the Casdoor token endpoint is unreachable or
     *         returns no {@code access_token}
     */
    public Mono<String> token() {
        return Mono.defer(() -> {
            // Re-use the cached token if it is still fresh.
            var c = cache.get();
            if (c != null && Instant.now().isBefore(c.staleAt())) {
                return Mono.just(c.token());
            }
            // Cache is absent or expired — mint a fresh one.
            return mint();
        });
    }

    /**
     * Force the next {@link #token()} call to re-mint by clearing
     * the cache. Should be called by the caller when it receives a
     * 401 from the federation server, which indicates the cached
     * token has been revoked or has expired on the server side
     * ahead of the client-side stale threshold.
     *
     * <p>Thread-safe: the atomic clear is immediately visible to
     * all threads.
     */
    public void invalidate() {
        cache.set(null);
    }

    /**
     * Perform an OAuth2 client-credentials token request against
     * Casdoor and cache the result.
     *
     * <p>Reactive pipeline:
     * <pre>
     *   POST {tokenUrl}
     *       (form-urlencoded: grant_type, client_id, client_secret)
     *   -&gt; bodyToMono(Map)
     *   -&gt; flatMap: extract access_token + expires_in,
     *               compute staleAt, store in cache
     *   -&gt; Mono&lt;String&gt; (the raw JWT)
     * </pre>
     *
     * @return a {@link Mono} that emits the freshly minted bearer
     *         token string
     * @throws IllegalStateException if Casdoor returns a 200
     *         response but the {@code access_token} field is missing
     *         or blank
     */
    private Mono<String> mint() {
        // Build the form body required by the client-credentials grant.
        var form = new LinkedMultiValueMap<String, String>();
        form.add("grant_type", "client_credentials");
        form.add("client_id", clientId);
        form.add("client_secret", clientSecret);
        return http.post()
                // POST to the token endpoint with form-encoded body.
                .uri(tokenPath)
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .body(BodyInserters.fromFormData(form))
                .retrieve()
                // Read the full response into a generic object map so
                // we can inspect both access_token and expires_in
                // without binding to a DTO. Under Boot 4 the reactive
                // JSON codec is Jackson 3; decoding into a Map keeps
                // this DTO-free read independent of the Jackson major
                // version (the platform pattern used by the fleet's
                // Casdoor mint clients).
                .bodyToMono(JSON_OBJECT)
                .flatMap(json -> {
                    // access_token MUST be present as a non-blank
                    // string, else the mint response is unusable.
                    Object accessToken = json.get("access_token");
                    if (!(accessToken instanceof String tok)
                            || tok.isBlank()) {
                        return Mono.error(new IllegalStateException(
                                "casdoor token mint returned no "
                                + "access_token: " + json));
                    }
                    // Prefer the server-stated lifetime; fall back to
                    // the default TTL when expires_in is missing or
                    // is not a JSON number.
                    Object expires = json.get("expires_in");
                    long expiresIn = expires instanceof Number num
                            ? num.longValue() : 0L;
                    Duration ttl = expiresIn > 0
                            ? Duration.ofSeconds(expiresIn)
                            : DEFAULT_TTL;
                    // Subtract EXPIRY_SKEW so we stop using the
                    // token slightly before its stated expiry.
                    Instant staleAt =
                            Instant.now().plus(ttl).minus(EXPIRY_SKEW);
                    cache.set(new Cached(tok, staleAt));
                    LOG.info(
                            "[casdoor] minted service token for {}"
                            + " (ttl ~{}s)",
                            clientId, ttl.toSeconds());
                    return Mono.just(tok);
                });
    }

    /**
     * Immutable snapshot of a cached token and the instant at which
     * it should be considered stale and re-minted.
     *
     * @param token   the raw RS256 JWT bearer string to include in
     *                Authorization headers
     * @param staleAt the instant before which {@code token} may be
     *                used without re-minting; computed as
     *                {@code Instant.now() + expires_in - EXPIRY_SKEW}
     */
    private record Cached(String token, Instant staleAt) { }
}
