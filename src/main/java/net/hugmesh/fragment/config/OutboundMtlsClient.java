package net.hugmesh.fragment.config;

import io.netty.handler.ssl.SslContext;
import io.netty.handler.ssl.SslContextBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.ConfigurationProperties;
import reactor.netty.http.client.HttpClient;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.TrustManagerFactory;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;

/**
 * Outbound mTLS for the call to federation-api: presents a CLIENT certificate and trusts
 * the cluster CA.
 *
 * <p>Why this exists. federation-api serves TLS with {@code client-auth=need}: a client
 * without a certificate chaining to the cluster CA is refused at the handshake. This
 * service had no client leg at all, so its sync was cut off in the cluster
 * (hugmesh-generator#507, finding 25). The shape and the property prefix are the ones
 * admin-bff's {@code OutboundMtlsClient} uses, so the SAME environment variables mean the
 * same thing in every service of the fleet:
 *
 * <pre>
 *   HUGMESH_MTLS_CLIENT_ENABLED=true
 *   HUGMESH_MTLS_CLIENT_KEYSTORE=/stores/keystore.p12        HUGMESH_MTLS_CLIENT_KEYSTOREPASSWORD=...
 *   HUGMESH_MTLS_CLIENT_TRUSTSTORE=/stores/truststore.p12    HUGMESH_MTLS_CLIENT_TRUSTSTOREPASSWORD=...
 * </pre>
 *
 * <p>Default OFF. With it off nothing changes (a plain {@link HttpClient}), so an older
 * deployment, and every unit test, is untouched. Only the FEDERATION client uses this: the
 * Casdoor token call stays plain http (Casdoor is not an mTLS peer).
 */
@ConfigurationProperties(prefix = "hugmesh.mtls.client")
public class OutboundMtlsClient {

    private static final Logger LOG = LoggerFactory.getLogger(OutboundMtlsClient.class);

    private boolean enabled = false;
    private String keyStore = "/stores/keystore.p12";
    private String keyStorePassword = "";
    private String keyStoreType = "PKCS12";
    private String trustStore = "/stores/truststore.p12";
    private String trustStorePassword = "";
    private String trustStoreType = "PKCS12";

    /** @return whether outbound client-certificate presentation is switched on */
    public boolean isEnabled() {
        return enabled;
    }

    /** @param value whether to present a client certificate */
    public void setEnabled(final boolean value) {
        this.enabled = value;
    }

    /** @return path of the client key store (certificate + private key) */
    public String getKeyStore() {
        return keyStore;
    }

    /** @param value path of the client key store */
    public void setKeyStore(final String value) {
        this.keyStore = value;
    }

    /** @return the key store password */
    public String getKeyStorePassword() {
        return keyStorePassword;
    }

    /** @param value the key store password */
    public void setKeyStorePassword(final String value) {
        this.keyStorePassword = value;
    }

    /** @return the key store type */
    public String getKeyStoreType() {
        return keyStoreType;
    }

    /** @param value the key store type */
    public void setKeyStoreType(final String value) {
        this.keyStoreType = value;
    }

    /** @return path of the trust store holding the cluster CA */
    public String getTrustStore() {
        return trustStore;
    }

    /** @param value path of the trust store */
    public void setTrustStore(final String value) {
        this.trustStore = value;
    }

    /** @return the trust store password */
    public String getTrustStorePassword() {
        return trustStorePassword;
    }

    /** @param value the trust store password */
    public void setTrustStorePassword(final String value) {
        this.trustStorePassword = value;
    }

    /** @return the trust store type */
    public String getTrustStoreType() {
        return trustStoreType;
    }

    /** @param value the trust store type */
    public void setTrustStoreType(final String value) {
        this.trustStoreType = value;
    }

    /**
     * Whether a client certificate will really be presented: switched on AND the key store
     * exists. Enabled with no key store warns loudly and presents nothing (the init
     * container that writes the stores always runs first in-cluster).
     *
     * @return true when {@link #customize(HttpClient)} will change the client
     */
    public boolean shouldApply() {
        if (!enabled) {
            return false;
        }
        final boolean present = keyStore != null && Files.exists(Path.of(keyStore));
        if (!present) {
            LOG.warn("[mtls-client] enabled but key-store '{}' is absent: outbound mTLS NOT applied", keyStore);
        }
        return present;
    }

    /**
     * Add the client identity to a Reactor Netty client, or return it unchanged.
     *
     * @param httpClient the client to configure
     * @return the same client, or one with an {@link SslContext} presenting the identity
     */
    public HttpClient customize(final HttpClient httpClient) {
        if (!shouldApply()) {
            return httpClient;
        }
        try {
            final SslContext ssl = buildSslContext();
            LOG.info("[mtls-client] outbound client-cert presentation ENABLED (key-store={}, trust-store={})",
                    keyStore, trustStore);
            return httpClient.secure(spec -> spec.sslContext(ssl));
        } catch (Exception e) {
            throw new IllegalStateException(
                    "Failed to build outbound mTLS SslContext from " + keyStore + " / " + trustStore, e);
        }
    }

    /** @return a client with the identity when it applies, else a plain one */
    public HttpClient httpClient() {
        return customize(HttpClient.create());
    }

    SslContext buildSslContext() throws Exception {
        final KeyManagerFactory kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        kmf.init(load(keyStore, keyStoreType, keyStorePassword), pw(keyStorePassword));
        final SslContextBuilder builder = SslContextBuilder.forClient().keyManager(kmf);
        // The cluster CA. With no trust store mounted, fall back to the JVM default trust.
        if (trustStore != null && Files.exists(Path.of(trustStore))) {
            final TrustManagerFactory tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
            tmf.init(load(trustStore, trustStoreType, trustStorePassword));
            builder.trustManager(tmf);
        } else {
            LOG.warn("[mtls-client] trust-store '{}' is absent: falling back to the JVM default trust", trustStore);
        }
        return builder.build();
    }

    private static KeyStore load(final String path, final String type, final String password) throws Exception {
        final KeyStore ks = KeyStore.getInstance(type == null ? "PKCS12" : type);
        try (InputStream in = Files.newInputStream(Path.of(path))) {
            ks.load(in, pw(password));
        }
        return ks;
    }

    private static char[] pw(final String password) {
        return password == null ? new char[0] : password.toCharArray();
    }
}
