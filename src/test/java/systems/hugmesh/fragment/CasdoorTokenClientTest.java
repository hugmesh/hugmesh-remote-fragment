package systems.hugmesh.fragment;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.reactive.function.client.ClientRequest;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.ExchangeFunction;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;
import systems.hugmesh.fragment.casdoor.CasdoorTokenClient;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies the client-credentials token mint: it POSTs the grant, returns the
 * access_token, caches it (no second mint while fresh), and re-mints after
 * {@link CasdoorTokenClient#invalidate()}.
 */
class CasdoorTokenClientTest {

    static class TokenRecorder implements ExchangeFunction {
        final List<ClientRequest> requests = new ArrayList<>();
        final AtomicInteger mints = new AtomicInteger();

        @Override
        public Mono<ClientResponse> exchange(ClientRequest request) {
            requests.add(request);
            int n = mints.incrementAndGet();
            String body = "{\"access_token\":\"jwt-" + n + "\",\"token_type\":\"Bearer\",\"expires_in\":3600}";
            return Mono.just(ClientResponse.create(HttpStatus.OK)
                    .header("Content-Type", MediaType.APPLICATION_JSON_VALUE)
                    .body(body).build());
        }
    }

    private CasdoorTokenClient clientFor(TokenRecorder rec) {
        WebClient web = WebClient.builder().exchangeFunction(rec).build();
        return new CasdoorTokenClient(web, "http://casdoor.test/api/login/oauth/access_token", "app-fragment-sync", "shh");
    }

    @Test
    void mintsAndReturnsAccessToken() {
        TokenRecorder rec = new TokenRecorder();
        CasdoorTokenClient client = clientFor(rec);

        StepVerifier.create(client.token()).expectNext("jwt-1").verifyComplete();
        // form-encoded client_credentials grant POSTed to the token URL
        assertThat(rec.requests).hasSize(1);
        assertThat(rec.requests.get(0).url().getPath()).endsWith("/access_token");
    }

    @Test
    void cachesToken_doesNotReMintWhileFresh() {
        TokenRecorder rec = new TokenRecorder();
        CasdoorTokenClient client = clientFor(rec);

        client.token().block();
        client.token().block();
        client.token().block();

        assertThat(rec.mints.get()).isEqualTo(1);   // cached after the first mint
    }

    @Test
    void reMintsAfterInvalidate() {
        TokenRecorder rec = new TokenRecorder();
        CasdoorTokenClient client = clientFor(rec);

        assertThat(client.token().block()).isEqualTo("jwt-1");
        client.invalidate();
        assertThat(client.token().block()).isEqualTo("jwt-2");
        assertThat(rec.mints.get()).isEqualTo(2);
    }
}
