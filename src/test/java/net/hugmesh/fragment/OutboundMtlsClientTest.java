package net.hugmesh.fragment;

import net.hugmesh.fragment.config.OutboundMtlsClient;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.SystemEnvironmentPropertySource;
import reactor.netty.http.client.HttpClient;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** The switch, its defaults and the env-var names (hugmesh-generator#507, finding 25). */
class OutboundMtlsClientTest {

    @Configuration
    @EnableConfigurationProperties(OutboundMtlsClient.class)
    static class Props {
    }

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(Props.class);

    @Test
    @DisplayName("default OFF: nothing changes, the client handed back is the one passed in")
    void defaultIsOffAndLeavesTheClientAlone() {
        runner.run(ctx -> {
            final OutboundMtlsClient m = ctx.getBean(OutboundMtlsClient.class);
            assertThat(m.isEnabled()).isFalse();
            assertThat(m.shouldApply()).isFalse();
            final HttpClient plain = HttpClient.create();
            assertThat(m.customize(plain)).isSameAs(plain);
        });
    }

    @Test
    @DisplayName("enabled but the key store is absent: warned and NOT applied, never a half-configured client")
    void enabledWithoutAKeyStoreAppliesNothing() {
        runner.withPropertyValues("hugmesh.mtls.client.enabled=true",
                "hugmesh.mtls.client.key-store=/nonexistent/keystore.p12").run(ctx -> {
            final OutboundMtlsClient m = ctx.getBean(OutboundMtlsClient.class);
            assertThat(m.isEnabled()).isTrue();
            assertThat(m.shouldApply()).isFalse();
        });
    }

    @Test
    @DisplayName("the contract the Deployment relies on, the same as admin-bff's: the paths default to what "
            + "the store-building init container writes (/stores/keystore.p12, /stores/truststore.p12)")
    void pathsDefaultToWhatTheInitContainerWrites() {
        runner.run(ctx -> {
            final OutboundMtlsClient m = ctx.getBean(OutboundMtlsClient.class);
            assertThat(m.getKeyStore()).isEqualTo("/stores/keystore.p12");
            assertThat(m.getTrustStore()).isEqualTo("/stores/truststore.p12");
            assertThat(m.getKeyStoreType()).isEqualTo("PKCS12");
            assertThat(m.getTrustStoreType()).isEqualTo("PKCS12");
        });
    }

    @Test
    @DisplayName("the switch is the ONE env var the Deployment sets (HUGMESH_MTLS_CLIENT_ENABLED), and the "
            + "passwords arrive as the kebab-case keys the init container writes into application.properties")
    void switchComesFromTheEnvironmentAndPasswordsFromThePropertiesFile() {
        // a SystemEnvironmentPropertySource is what a pod's real environment is: only it gets
        // Spring's env-var relaxed binding
        runner.withInitializer(ctx -> ctx.getEnvironment().getPropertySources().addFirst(
                        new SystemEnvironmentPropertySource("test-env", Map.of("HUGMESH_MTLS_CLIENT_ENABLED", "true"))))
                .withPropertyValues("hugmesh.mtls.client.key-store-password=pw1",
                        "hugmesh.mtls.client.trust-store-password=pw2")
                .run(ctx -> {
                    final OutboundMtlsClient m = ctx.getBean(OutboundMtlsClient.class);
                    assertThat(m.isEnabled()).isTrue();
                    assertThat(m.getKeyStorePassword()).isEqualTo("pw1");
                    assertThat(m.getTrustStorePassword()).isEqualTo("pw2");
                });
    }
}
