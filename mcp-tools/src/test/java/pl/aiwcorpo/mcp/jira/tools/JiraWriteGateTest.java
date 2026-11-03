package pl.aiwcorpo.mcp.jira.tools;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Set;
import org.junit.jupiter.api.Test;
import pl.aiwcorpo.mcp.jira.core.domain.BackendDegradedException;
import pl.aiwcorpo.mcp.platform.capability.Capabilities;
import pl.aiwcorpo.mcp.platform.error.ToolErrorCode;

class JiraWriteGateTest {

    @Test
    void aTestedVersionMayBeChanged() {
        JiraWriteGate gate = new JiraWriteGate(() -> Capabilities.supported("10.3.5", Set.of("issue_read", "issue_write")));

        assertThatCode(gate::requireWritable).doesNotThrowAnyException();
    }

    @Test
    void aDegradedBackendServesReadsOnly() {
        JiraWriteGate gate = new JiraWriteGate(() -> Capabilities.degraded("12.0.0", Set.of("issue_read"), "newer than tested"));

        assertThatThrownBy(gate::requireWritable).isInstanceOf(BackendDegradedException.class);
    }

    @Test
    void anUnavailableProbeDoesNotBlockWrites() {
        JiraWriteGate gate = new JiraWriteGate(() -> Capabilities.unavailable("not probed yet"));

        assertThatCode(gate::requireWritable).doesNotThrowAnyException();
    }

    @Test
    void theRefusalIsAnUpstreamUnavailableWithAFixedText() {
        var error = new JiraToolErrors().map(new BackendDegradedException()).orElseThrow();

        assertThat(error.code()).isEqualTo(ToolErrorCode.UPSTREAM_UNAVAILABLE.code());
        assertThat(error.message()).contains("not supported for changes").contains("Nothing was changed");
    }
}
