package pl.aiwcorpo.mcp.jira.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import pl.aiwcorpo.mcp.jira.core.domain.ObservedVersions;
import pl.aiwcorpo.mcp.jira.core.domain.ServerInfo;
import pl.aiwcorpo.mcp.platform.capability.Capabilities;
import pl.aiwcorpo.mcp.platform.capability.Capabilities.State;

class JiraCapabilityProbeTest {

    @ParameterizedTest
    @ValueSource(strings = {"9.12.0", "9.12.14", "9.17.1", "10.3.5", "10.4.0", "10.7.2", "11.0.0", "11.3.0", "11.99.1"})
    void aTestedDataCenterVersionMayBeChanged(String version) {
        Capabilities found = JiraCapabilityProbe.classify(new ServerInfo(version, "Server"));

        assertThat(found.state()).isEqualTo(State.SUPPORTED);
        assertThat(found.features()).contains(JiraCapabilityProbe.ISSUE_READ, JiraCapabilityProbe.ISSUE_WRITE,
                JiraCapabilityProbe.SEARCH_OFFSET);
    }

    @ParameterizedTest
    @ValueSource(strings = {"8.14.0", "8.20.10", "9.11.3", "9.0.0", "12.0.0", "13.2.1"})
    void aVersionNobodyTestedOnlyReads(String version) {
        Capabilities found = JiraCapabilityProbe.classify(new ServerInfo(version, "Server"));

        assertThat(found.state()).isEqualTo(State.DEGRADED);
        assertThat(found.features()).containsExactly(JiraCapabilityProbe.ISSUE_READ);
        assertThat(found.detail()).isNotBlank();
    }

    @Test
    void cloudIsDegradedWhateverTheVersionSays() {
        Capabilities found = JiraCapabilityProbe.classify(new ServerInfo("1001.0.0-SNAPSHOT", "Cloud"));

        assertThat(found.state()).isEqualTo(State.DEGRADED);
        assertThat(JiraCapabilityProbe.classify(new ServerInfo("10.3.5", "cloud")).state()).isEqualTo(State.DEGRADED);
        assertThat(found.features()).doesNotContain(JiraCapabilityProbe.ISSUE_WRITE);
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "  ", "unknown", "<html>login</html>", "v", "9"})
    void anAnswerWithoutAVersionIsUnavailableNotGuessed(String version) {
        Capabilities found = JiraCapabilityProbe.classify(new ServerInfo(version, "Server"));

        assertThat(found.state()).isEqualTo(State.UNAVAILABLE);
        assertThat(found.features()).isEmpty();
    }

    @Test
    void aMissingVersionIsUnavailable() {
        assertThat(JiraCapabilityProbe.classify(new ServerInfo(null, null)).state()).isEqualTo(State.UNAVAILABLE);
    }

    @Test
    void theProbeAsksTheBackendWithoutACredential() throws Exception {
        UnsupportedJira jira = new UnsupportedJira() {
            @Override
            public ServerInfo serverInfo() {
                return new ServerInfo("10.3.5", "Server");
            }
        };

        assertThat(new JiraCapabilityProbe(jira, new ObservedVersions()).probe().backendVersion()).isEqualTo("10.3.5");
        assertThat(new JiraCapabilityProbe(jira, new ObservedVersions()).name()).isEqualTo("jira");
    }

    @Test
    void aFailingBackendFailsTheProbeAndTheStarterReportsUnavailable() {
        UnsupportedJira jira = new UnsupportedJira() { };

        assertThatThrownBy(() -> new JiraCapabilityProbe(jira, new ObservedVersions()).probe()).isInstanceOf(UnsupportedOperationException.class);
    }

    /** A probe whose answer the test changes between runs: a version, or a failure. */
    private static final class ScriptedJira extends UnsupportedJira {
        ServerInfo answer = new ServerInfo("11.3.0", "Server");
        RuntimeException failure;

        @Override
        public ServerInfo serverInfo() {
            if (failure != null) {
                throw failure;
            }
            return answer;
        }
    }

    @Test
    void aFailingRunAfterAnUntestedFindingKeepsTheFinding() throws Exception {
        ScriptedJira jira = new ScriptedJira();
        ObservedVersions observed = new ObservedVersions();
        JiraCapabilityProbe probe = new JiraCapabilityProbe(jira, observed);
        jira.answer = new ServerInfo("12.1.0", "Server");
        assertThat(probe.probe().state()).isEqualTo(State.DEGRADED);

        jira.failure = new IllegalStateException("502");
        Capabilities afterFailure = probe.probe();
        jira.failure = null;
        jira.answer = new ServerInfo(null, null);
        Capabilities afterUnreadable = probe.probe();

        assertThat(afterFailure.state()).as("one failed run does not reopen writes").isEqualTo(State.DEGRADED);
        assertThat(afterUnreadable.state()).isEqualTo(State.DEGRADED);
        assertThat(observed.untestedSeen()).isTrue();
    }

    @Test
    void aTestedVersionClearsTheFindingAndAFailureBeforeAnyFindingStaysAFailure() throws Exception {
        ScriptedJira jira = new ScriptedJira();
        ObservedVersions observed = new ObservedVersions();
        JiraCapabilityProbe probe = new JiraCapabilityProbe(jira, observed);
        jira.failure = new IllegalStateException("timeout");
        assertThatThrownBy(probe::probe).isInstanceOf(IllegalStateException.class);
        assertThat(observed.untestedSeen()).isFalse();

        jira.failure = null;
        jira.answer = new ServerInfo("8.14.0", "Server");
        probe.probe();
        assertThat(observed.untestedSeen()).isTrue();
        jira.answer = new ServerInfo("10.3.5", "Server");
        assertThat(probe.probe().state()).isEqualTo(State.SUPPORTED);
        assertThat(observed.untestedSeen()).isFalse();
        jira.failure = new IllegalStateException("502");
        assertThatThrownBy(probe::probe).isInstanceOf(IllegalStateException.class);
    }
}
