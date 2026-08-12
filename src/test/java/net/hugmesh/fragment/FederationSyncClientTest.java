package net.hugmesh.fragment;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.reactive.function.client.ClientRequest;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.ExchangeFunction;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;
import net.hugmesh.fragment.casdoor.CasdoorTokenClient;
import net.hugmesh.fragment.federation.FederationSyncClient;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies that FederationSyncClient invalidates the cached token exactly when
 * the federation endpoint responds with 401 or 403, but NOT on other errors
 * such as 500.
 *
 * <p>Each test wires a minimal ExchangeFunction that returns a fixed status for
 * the federation call and records whether {@link CasdoorTokenClient#invalidate()}
 * was called (via a subclass that sets a flag).
 */
class FederationSyncClientTest {

    /**
     * CasdoorTokenClient subclass that records invalidate() calls without
     * touching the actual OAuth flow.
     */
    static class TrackingTokenClient extends CasdoorTokenClient {
        final AtomicInteger invalidateCalls = new AtomicInteger(0);

        TrackingTokenClient(WebClient web) {
            super(web, "http://casdoor.test/access_token", "app-test", "secret");
        }

        @Override
        public void invalidate() {
            invalidateCalls.incrementAndGet();
            super.invalidate();
        }
    }

    /** ExchangeFunction that answers the Casdoor mint with a valid token and
     *  all federation calls with the supplied HTTP status. */
    static ExchangeFunction stubWithStatus(HttpStatus federationStatus) {
        return request -> {
            String path = request.url().getPath();
            if (path.endsWith("/access_token")) {
                return Mono.just(ClientResponse.create(HttpStatus.OK)
                        .header("Content-Type", MediaType.APPLICATION_JSON_VALUE)
                        .body("{\"access_token\":\"test.jwt\",\"token_type\":\"Bearer\",\"expires_in\":3600}")
                        .build());
            }
            // All other paths are federation endpoints — return the configured status.
            return Mono.just(ClientResponse.create(federationStatus)
                    .header("Content-Type", MediaType.TEXT_PLAIN_VALUE)
                    .body("error")
                    .build());
        };
    }

    private FederationSyncClient clientFor(TrackingTokenClient tokens, HttpStatus federationStatus) {
        WebClient web = WebClient.builder()
                .exchangeFunction(stubWithStatus(federationStatus))
                .baseUrl("http://federation.test")
                .build();
        return new FederationSyncClient(web, tokens);
    }

    private TrackingTokenClient trackingTokens(HttpStatus federationStatus) {
        // The token client also uses the same exchange function so it can mint.
        WebClient web = WebClient.builder()
                .exchangeFunction(stubWithStatus(federationStatus))
                .build();
        return new TrackingTokenClient(web);
    }

    // ── manifest() ──────────────────────────────────────────────────────────

    @Test
    void manifest_401_invalidatesToken() {
        TrackingTokenClient tokens = trackingTokens(HttpStatus.UNAUTHORIZED);
        FederationSyncClient client = clientFor(tokens, HttpStatus.UNAUTHORIZED);

        StepVerifier.create(client.manifest())
                .expectError()
                .verify();

        assertThat(tokens.invalidateCalls.get())
                .as("invalidate() must be called once on 401 from manifest")
                .isEqualTo(1);
    }

    @Test
    void manifest_403_invalidatesToken() {
        TrackingTokenClient tokens = trackingTokens(HttpStatus.FORBIDDEN);
        FederationSyncClient client = clientFor(tokens, HttpStatus.FORBIDDEN);

        StepVerifier.create(client.manifest())
                .expectError()
                .verify();

        assertThat(tokens.invalidateCalls.get())
                .as("invalidate() must be called once on 403 from manifest")
                .isEqualTo(1);
    }

    @Test
    void manifest_500_doesNotInvalidateToken() {
        TrackingTokenClient tokens = trackingTokens(HttpStatus.INTERNAL_SERVER_ERROR);
        FederationSyncClient client = clientFor(tokens, HttpStatus.INTERNAL_SERVER_ERROR);

        StepVerifier.create(client.manifest())
                .expectError()
                .verify();

        assertThat(tokens.invalidateCalls.get())
                .as("invalidate() must NOT be called on 500 from manifest")
                .isZero();
    }

    // ── bundle() ────────────────────────────────────────────────────────────

    @Test
    void bundle_401_invalidatesToken() {
        TrackingTokenClient tokens = trackingTokens(HttpStatus.UNAUTHORIZED);
        FederationSyncClient client = clientFor(tokens, HttpStatus.UNAUTHORIZED);

        StepVerifier.create(client.bundle("abc123"))
                .expectError()
                .verify();

        assertThat(tokens.invalidateCalls.get())
                .as("invalidate() must be called once on 401 from bundle()")
                .isEqualTo(1);
    }

    @Test
    void bundle_403_invalidatesToken() {
        TrackingTokenClient tokens = trackingTokens(HttpStatus.FORBIDDEN);
        FederationSyncClient client = clientFor(tokens, HttpStatus.FORBIDDEN);

        StepVerifier.create(client.bundle("abc123"))
                .expectError()
                .verify();

        assertThat(tokens.invalidateCalls.get())
                .as("invalidate() must be called once on 403 from bundle()")
                .isEqualTo(1);
    }

    @Test
    void bundle_500_doesNotInvalidateToken() {
        TrackingTokenClient tokens = trackingTokens(HttpStatus.INTERNAL_SERVER_ERROR);
        FederationSyncClient client = clientFor(tokens, HttpStatus.INTERNAL_SERVER_ERROR);

        StepVerifier.create(client.bundle("abc123"))
                .expectError()
                .verify();

        assertThat(tokens.invalidateCalls.get())
                .as("invalidate() must NOT be called on 500 from bundle()")
                .isZero();
    }

    // ── file() ──────────────────────────────────────────────────────────────

    @Test
    void file_401_invalidatesToken() {
        TrackingTokenClient tokens = trackingTokens(HttpStatus.UNAUTHORIZED);
        FederationSyncClient client = clientFor(tokens, HttpStatus.UNAUTHORIZED);

        StepVerifier.create(client.file("abc123", "remoteEntry.json").collectList())
                .expectError()
                .verify();

        assertThat(tokens.invalidateCalls.get())
                .as("invalidate() must be called once on 401 from file()")
                .isEqualTo(1);
    }

    @Test
    void file_403_invalidatesToken() {
        TrackingTokenClient tokens = trackingTokens(HttpStatus.FORBIDDEN);
        FederationSyncClient client = clientFor(tokens, HttpStatus.FORBIDDEN);

        StepVerifier.create(client.file("abc123", "remoteEntry.json").collectList())
                .expectError()
                .verify();

        assertThat(tokens.invalidateCalls.get())
                .as("invalidate() must be called once on 403 from file()")
                .isEqualTo(1);
    }

    @Test
    void file_500_doesNotInvalidateToken() {
        TrackingTokenClient tokens = trackingTokens(HttpStatus.INTERNAL_SERVER_ERROR);
        FederationSyncClient client = clientFor(tokens, HttpStatus.INTERNAL_SERVER_ERROR);

        StepVerifier.create(client.file("abc123", "remoteEntry.json").collectList())
                .expectError()
                .verify();

        assertThat(tokens.invalidateCalls.get())
                .as("invalidate() must NOT be called on 500 from file()")
                .isZero();
    }
}
