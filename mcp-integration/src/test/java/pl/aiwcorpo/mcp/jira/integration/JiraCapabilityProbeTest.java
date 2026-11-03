package pl.aiwcorpo.mcp.jira.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
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

        assertThat(new JiraCapabilityProbe(jira).probe().backendVersion()).isEqualTo("10.3.5");
        assertThat(new JiraCapabilityProbe(jira).name()).isEqualTo("jira");
    }

    @Test
    void aFailingBackendFailsTheProbeAndTheStarterReportsUnavailable() {
        UnsupportedJira jira = new UnsupportedJira() { };

        assertThatThrownBy(() -> new JiraCapabilityProbe(jira).probe()).isInstanceOf(UnsupportedOperationException.class);
    }
}
