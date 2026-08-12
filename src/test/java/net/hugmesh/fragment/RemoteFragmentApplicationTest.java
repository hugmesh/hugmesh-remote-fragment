package net.hugmesh.fragment;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import net.hugmesh.fragment.casdoor.CasdoorTokenClient;
import net.hugmesh.fragment.federation.FederationSyncClient;
import net.hugmesh.fragment.sync.FragmentSyncRunner;
import net.hugmesh.fragment.sync.FragmentSyncService;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Context-load smoke test: the full WebFlux + actuator context wires (token
 * client, federation client, sync service, runner, health indicator) with the
 * default env-driven config.
 *
 * <p>Runs in loop mode with a far-future interval and an unreachable federation
 * host — the runner's initial tick fails fast and is swallowed by onErrorResume
 * (so a federation-less boot is clean), which is exactly the production
 * self-healing behaviour. No System.exit (that is only once-mode). target-dir is
 * /tmp so no real nginx volume is needed.
 */
@SpringBootTest(properties = {
        "sync.mode=loop",
        "sync.interval=PT24H",
        "sync.target-dir=${java.io.tmpdir}/remote-fragment-ctxtest",
        "federation.base-url=http://federation.invalid:8080",
        "casdoor.client-secret=test"
})
class RemoteFragmentApplicationTest {

    @Autowired CasdoorTokenClient tokenClient;
    @Autowired FederationSyncClient federationClient;
    @Autowired FragmentSyncService syncService;
    @Autowired FragmentSyncRunner runner;

    @Test
    void contextLoads() {
        assertThat(tokenClient).isNotNull();
        assertThat(federationClient).isNotNull();
        assertThat(syncService).isNotNull();
        assertThat(runner).isNotNull();
    }
}
