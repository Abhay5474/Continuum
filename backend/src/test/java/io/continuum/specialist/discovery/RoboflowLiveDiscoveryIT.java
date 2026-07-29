package io.continuum.specialist.discovery;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.continuum.net.GuardedHttpSender;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The only test that talks to the real Roboflow.
 *
 * <p><b>It is skipped in this deployment, and that is reported rather than
 * hidden.</b> Every Roboflow host is refused at CONNECT here, so the test is
 * gated on {@code ROBOFLOW_API_KEY} being present and simply does not run
 * without one. A skipped test is not a passing test, and nothing in this
 * codebase claims the live path has been exercised: the status is
 * {@code LIVE_UNVERIFIED}.
 *
 * <p>Run it somewhere with network access and a real key to move the mapping in
 * {@link RoboflowDiscoveryMappingTest} from "correct against constructed
 * fixtures" to "correct against the service". If it fails there, the fixtures
 * are the thing to fix — they encode an assumption about a contract, and the
 * service is the authority.
 *
 * <pre>
 *   ROBOFLOW_API_KEY=… mvn test -Dtest=RoboflowLiveDiscoveryIT
 * </pre>
 *
 * <p>The key is read from the environment. It must never be written into this
 * file, a properties file, or a fixture.
 */
@EnabledIfEnvironmentVariable(named = "ROBOFLOW_API_KEY", matches = ".+")
class RoboflowLiveDiscoveryIT {

    @Test
    @DisplayName("the real directory answers, and the answer maps onto catalogue entries")
    void searchesTheRealDirectory() {
        String key = System.getenv("ROBOFLOW_API_KEY");

        HttpRoboflowDiscoveryClient client = new HttpRoboflowDiscoveryClient(
                new GuardedHttpSender(false), new ObjectMapper(), "https://api.roboflow.com", 15);

        RoboflowDiscoveryClient.SearchResponse res = client.search(key, "hard hat", 5);

        // A network failure here is a real result worth seeing, not a reason to
        // pass quietly — the whole point of this test is to find out.
        assertThat(res.status())
                .as("Roboflow search status; raw body was: %s", res.rawBody())
                .isEqualTo(RoboflowDiscoveryClient.Status.OK);
        assertThat(client.reachable()).isTrue();

        // Zero results is acceptable — the directory's contents are not ours to
        // assert on. What must hold is that anything returned is well formed.
        for (RoboflowDiscoveryClient.Model m : res.models()) {
            assertThat(m.name()).isNotBlank();
            if (m.invocable()) {
                assertThat(m.invocationPath()).matches(".+/\\d+");
            }
        }

        // The credential must not come back out through any developer-facing path.
        assertThat(res.detail()).doesNotContain(key);
        assertThat(String.valueOf(res.rawBody())).doesNotContain(key);
    }
}
