package net.hugmesh.fragment;

import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.ssl.ClientAuth;
import io.netty.handler.ssl.SslContext;
import io.netty.handler.ssl.SslContextBuilder;
import io.netty.handler.ssl.SslHandler;
import net.hugmesh.fragment.casdoor.CasdoorTokenClient;
import net.hugmesh.fragment.config.OutboundMtlsClient;
import net.hugmesh.fragment.federation.FederationManifest;
import net.hugmesh.fragment.federation.FederationSyncClient;
import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.asn1.x509.BasicConstraints;
import org.bouncycastle.asn1.x509.ExtendedKeyUsage;
import org.bouncycastle.asn1.x509.Extension;
import org.bouncycastle.asn1.x509.GeneralName;
import org.bouncycastle.asn1.x509.GeneralNames;
import org.bouncycastle.asn1.x509.KeyPurposeId;
import org.bouncycastle.cert.X509v3CertificateBuilder;
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter;
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder;
import org.bouncycastle.operator.ContentSigner;
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import net.hugmesh.fragment.config.SyncConfig;
import org.springframework.boot.convert.ApplicationConversionService;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.ExchangeFunction;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;
import reactor.netty.DisposableServer;
import reactor.netty.http.server.HttpServer;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLPeerUnverifiedException;
import javax.net.ssl.TrustManagerFactory;
import java.io.OutputStream;
import java.math.BigInteger;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.KeyStore;
import java.security.cert.Certificate;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The CLIENT CERTIFICATE really crosses the wire (hugmesh-generator#507, finding 25).
 *
 * <p>federation-api serves TLS with {@code client-auth=need}. This test stands up a real
 * Reactor Netty server with {@code ClientAuth.REQUIRE} that trusts one identity, builds the
 * federation client exactly as production does ({@code new FederationSyncClient(url,
 * tokens, mtls.httpClient())}), and shows: with the identity the manifest comes back and the
 * server saw our certificate; WITHOUT mTLS the handshake is refused and nothing reaches the
 * handler (the failure the cluster had); with a certificate the server does not trust it is
 * refused too (the server really enforces it, so the first case is not a vacuous pass).
 */
class FederationSyncClientMtlsWireTest {

    private static final char[] PW = "changeit".toCharArray();
    private static final String CN = "hugmesh-remote-fragment-wire-test";
    private static final Duration WIRE = Duration.ofSeconds(15);

    @TempDir
    static Path stores;
    private static Path keyStore;
    private static Path trustStore;
    private static Path rogueKeyStore;
    private static DisposableServer federation;
    private static DisposableServer casdoor;
    private static final AtomicInteger CALLS = new AtomicInteger();
    private static final AtomicReference<String> AUTHORIZATION = new AtomicReference<>();
    private static final AtomicReference<String> PEER_SUBJECT = new AtomicReference<>();

    @BeforeAll
    static void startFederation() throws Exception {
        final KeyPair kp = rsa();
        final X509Certificate cert = selfSigned(kp, CN);
        keyStore = stores.resolve("keystore.p12");
        trustStore = stores.resolve("truststore.p12");
        rogueKeyStore = stores.resolve("rogue.p12");
        final KeyStore ks = pkcs12();
        ks.setKeyEntry("svc", kp.getPrivate(), PW, new Certificate[] {cert});
        write(ks, keyStore);
        final KeyStore ts = pkcs12();
        ts.setCertificateEntry("cluster-ca", cert);
        write(ts, trustStore);
        final KeyPair rogue = rsa();
        final KeyStore rk = pkcs12();
        rk.setKeyEntry("svc", rogue.getPrivate(), PW, new Certificate[] {selfSigned(rogue, "rogue-not-in-the-cluster")});
        write(rk, rogueKeyStore);

        final KeyManagerFactory kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        kmf.init(ks, PW);
        final TrustManagerFactory tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        tmf.init(ts);
        final SslContext serverSsl = SslContextBuilder.forServer(kmf).trustManager(tmf)
                .clientAuth(ClientAuth.REQUIRE).build();
        federation = HttpServer.create().host("127.0.0.1").port(0)
                .secure(spec -> spec.sslContext(serverSsl))
                .route(r -> r.get("/api/federation/manifest", (req, res) -> {
                    CALLS.incrementAndGet();
                    AUTHORIZATION.set(req.requestHeaders().get(HttpHeaderNames.AUTHORIZATION));
                    req.withConnection(conn -> {
                        final SslHandler ssl = conn.channel().pipeline().get(SslHandler.class);
                        if (ssl != null) {
                            try {
                                final Certificate[] peer = ssl.engine().getSession().getPeerCertificates();
                                PEER_SUBJECT.set(((X509Certificate) peer[0]).getSubjectX500Principal().getName());
                            } catch (SSLPeerUnverifiedException e) {
                                PEER_SUBJECT.set("(no peer certificate)");
                            }
                        }
                    });
                    return res.header(HttpHeaderNames.CONTENT_TYPE, "application/json")
                            .sendString(Mono.just("{\"generatedAt\":\"now\",\"remotes\":[]}"));
                }))
                .bindNow();
        // a plain-http Casdoor: the token mint is NOT an mTLS peer and must stay plain
        casdoor = HttpServer.create().host("127.0.0.1").port(0)
                .route(r -> r.post("/api/login/oauth/access_token", (req, res) ->
                        req.receive().aggregate().then(Mono.defer(() -> res
                                .header(HttpHeaderNames.CONTENT_TYPE, "application/json")
                                .sendString(Mono.just("{\"access_token\":\"svc-jwt\",\"expires_in\":3600}")).then()))))
                .bindNow();
    }

    @AfterAll
    static void stopFederation() {
        if (federation != null) {
            federation.disposeNow();
        }
        if (casdoor != null) {
            casdoor.disposeNow();
        }
    }

    @BeforeEach
    void reset() {
        CALLS.set(0);
        AUTHORIZATION.set(null);
        PEER_SUBJECT.set(null);
    }

    private static KeyPair rsa() throws Exception {
        final KeyPairGenerator kpg = KeyPairGenerator.getInstance("RSA");
        kpg.initialize(2048);
        return kpg.generateKeyPair();
    }

    private static KeyStore pkcs12() throws Exception {
        final KeyStore ks = KeyStore.getInstance("PKCS12");
        ks.load(null, null);
        return ks;
    }

    private static void write(final KeyStore ks, final Path to) throws Exception {
        try (OutputStream out = Files.newOutputStream(to)) {
            ks.store(out, PW);
        }
    }

    private static X509Certificate selfSigned(final KeyPair kp, final String cn) throws Exception {
        final X500Name name = new X500Name("CN=" + cn + ",O=hugmesh");
        final Instant now = Instant.now();
        final X509v3CertificateBuilder b = new JcaX509v3CertificateBuilder(
                name, BigInteger.valueOf(now.toEpochMilli()),
                Date.from(now.minusSeconds(300)), Date.from(now.plusSeconds(3600)),
                name, kp.getPublic());
        b.addExtension(Extension.basicConstraints, true, new BasicConstraints(true));
        b.addExtension(Extension.extendedKeyUsage, false, new ExtendedKeyUsage(
                new KeyPurposeId[] {KeyPurposeId.id_kp_serverAuth, KeyPurposeId.id_kp_clientAuth}));
        b.addExtension(Extension.subjectAlternativeName, false, new GeneralNames(new GeneralName[] {
                new GeneralName(GeneralName.iPAddress, "127.0.0.1"),
                new GeneralName(GeneralName.dNSName, "localhost")}));
        final ContentSigner signer = new JcaContentSignerBuilder("SHA256withRSA").build(kp.getPrivate());
        return new JcaX509CertificateConverter().getCertificate(b.build(signer));
    }

    private static CasdoorTokenClient tokens() {
        final ExchangeFunction casdoor = request -> Mono.just(ClientResponse.create(HttpStatus.OK)
                .header("Content-Type", MediaType.APPLICATION_JSON_VALUE)
                .body("{\"access_token\":\"svc-jwt\",\"token_type\":\"Bearer\",\"expires_in\":3600}")
                .build());
        return new CasdoorTokenClient(WebClient.builder().exchangeFunction(casdoor).build(),
                "http://casdoor.test/api/login/oauth/access_token", "app-fragment-sync", "secret");
    }

    private static OutboundMtlsClient mtls(final boolean enabled, final Path identity) {
        final OutboundMtlsClient m = new OutboundMtlsClient();
        m.setEnabled(enabled);
        m.setKeyStore(identity.toString());
        m.setKeyStorePassword("changeit");
        m.setTrustStore(trustStore.toString());
        m.setTrustStorePassword("changeit");
        return m;
    }

    private static FederationSyncClient client(final OutboundMtlsClient m) {
        // exactly the production construction (SyncConfig.federationSyncClient)
        return new FederationSyncClient("https://127.0.0.1:" + federation.port(), tokens(), m.httpClient());
    }

    @Test
    @DisplayName("with the client identity: the handshake passes, the server sees OUR certificate, the "
            + "bearer rides along, and the manifest comes back")
    void withIdentity_manifestComesBack() {
        final FederationManifest manifest = client(mtls(true, keyStore)).manifest().block(WIRE);
        assertThat(manifest).isNotNull();
        assertThat(CALLS.get()).isEqualTo(1);
        assertThat(PEER_SUBJECT.get()).contains("CN=" + CN);
        assertThat(AUTHORIZATION.get()).isEqualTo("Bearer svc-jwt");
    }

    @Test
    @DisplayName("WITHOUT mTLS (the cluster's situation): the same TLS peer refuses the handshake and "
            + "nothing reaches the handler")
    void withoutMtls_isRefusedAtTheHandshake() {
        assertThatThrownBy(() -> client(mtls(false, keyStore)).manifest().block(WIRE)).isNotNull();
        assertThat(CALLS.get()).as("no request ever reached the handler").isZero();
    }

    @Test
    @DisplayName("a client certificate the server does NOT trust is refused too: the server really "
            + "enforces client-auth, so the passing case above is not vacuous")
    void rogueIdentity_isRefused() {
        assertThatThrownBy(() -> client(mtls(true, rogueKeyStore)).manifest().block(WIRE)).isNotNull();
        assertThat(CALLS.get()).isZero();
    }

    private ApplicationContextRunner production(final boolean enabled) {
        return new ApplicationContextRunner()
                // Boot's conversion service, as a real application has: SyncConfig takes a Duration @Value
                .withInitializer(ctx -> ctx.getBeanFactory().setConversionService(
                        ApplicationConversionService.getSharedInstance()))
                .withUserConfiguration(SyncConfig.class)
                .withPropertyValues(
                        "federation.base-url=https://127.0.0.1:" + federation.port(),
                        "casdoor.token-url=http://127.0.0.1:" + casdoor.port() + "/api/login/oauth/access_token",
                        "casdoor.client-secret=secret",
                        "sync.target-dir=" + stores.resolve("html"),
                        "sync.mode=once",
                        "hugmesh.mtls.client.enabled=" + enabled,
                        "hugmesh.mtls.client.key-store=" + keyStore,
                        "hugmesh.mtls.client.key-store-password=changeit",
                        "hugmesh.mtls.client.trust-store=" + trustStore,
                        "hugmesh.mtls.client.trust-store-password=changeit");
    }

    @Test
    @DisplayName("through the REAL SyncConfig wiring: HUGMESH_MTLS_CLIENT_ENABLED=true makes the federation "
            + "bean present the identity (a regression in SyncConfig would fail here, not only in a cluster)")
    void productionWiring_enabled_presentsTheIdentity() {
        production(true).run(ctx -> {
            assertThat(ctx).hasNotFailed();
            final FederationManifest m = ctx.getBean(FederationSyncClient.class).manifest().block(WIRE);
            assertThat(m).isNotNull();
            assertThat(PEER_SUBJECT.get()).contains("CN=" + CN);
        });
    }

    @Test
    @DisplayName("through the REAL SyncConfig wiring with the switch OFF (the default): no identity, so the "
            + "TLS peer refuses it, exactly the cluster's state before this change")
    void productionWiring_disabled_isRefused() {
        production(false).run(ctx -> {
            assertThat(ctx).hasNotFailed();
            assertThatThrownBy(() -> ctx.getBean(FederationSyncClient.class).manifest().block(WIRE)).isNotNull();
            assertThat(CALLS.get()).isZero();
        });
    }
}
