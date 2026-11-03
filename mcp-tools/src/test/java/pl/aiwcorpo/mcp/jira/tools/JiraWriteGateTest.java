package pl.aiwcorpo.mcp.jira.tools;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import pl.aiwcorpo.mcp.jira.core.domain.BackendDegradedException;
import pl.aiwcorpo.mcp.jira.core.domain.ObservedVersions;
import pl.aiwcorpo.mcp.platform.capability.Capabilities;
import pl.aiwcorpo.mcp.platform.error.ToolErrorCode;

class JiraWriteGateTest {

    private static final Capabilities SUPPORTED = Capabilities.supported("10.3.5", Set.of("issue_read", "issue_write"));
    private static final Capabilities DEGRADED = Capabilities.degraded("12.0.0", Set.of("issue_read"), "newer than tested");
    private static final Capabilities UNAVAILABLE = Capabilities.unavailable("not probed yet");

    private final AtomicReference<Capabilities> state = new AtomicReference<>(UNAVAILABLE);
    private final ObservedVersions observed = new ObservedVersions();

    private JiraWriteGate gate(boolean writesOnUnknownVersion) {
        return new JiraWriteGate(state::get, observed, writesOnUnknownVersion);
    }

    @Test
    void aTestedVersionMayBeChanged() {
        state.set(SUPPORTED);

        assertThatCode(gate(false)::requireWritable).doesNotThrowAnyException();
    }

    @Test
    void aSupportedStateWithoutTheWriteFeatureIsClosed() {
        state.set(Capabilities.supported("10.3.5", Set.of("issue_read")));

        assertThatThrownBy(gate(true)::requireWritable).isInstanceOf(BackendDegradedException.class);
    }

    @Test
    void aDegradedBackendServesReadsOnlyWhateverTheSetting() {
        state.set(DEGRADED);

        assertThatThrownBy(gate(false)::requireWritable).isInstanceOf(BackendDegradedException.class);
        assertThatThrownBy(gate(true)::requireWritable).isInstanceOf(BackendDegradedException.class);
    }

    @Test
    void beforeTheFirstProbeAndWhileTheVersionIsUnreadableWritesAreClosed() {
        state.set(UNAVAILABLE);

        assertThatThrownBy(gate(false)::requireWritable).isInstanceOf(BackendDegradedException.class);
    }

    @Test
    void theSettingOpensWritesOnlyInTheUnavailableState() {
        state.set(UNAVAILABLE);

        assertThatCode(gate(true)::requireWritable).doesNotThrowAnyException();
    }

    @Test
    void anUnavailableStateAfterAnUntestedFindingStaysClosedEvenWithTheSetting() {
        observed.untested();
        state.set(UNAVAILABLE);

        assertThatThrownBy(gate(true)::requireWritable).isInstanceOf(BackendDegradedException.class);
        observed.tested();
        assertThatCode(gate(true)::requireWritable).doesNotThrowAnyException();
    }

    @Test
    void theRefusalIsAnUpstreamUnavailableWithAFixedText() {
        var error = new JiraToolErrors().map(new BackendDegradedException()).orElseThrow();

        assertThat(error.code()).isEqualTo(ToolErrorCode.UPSTREAM_UNAVAILABLE.code());
        assertThat(error.message()).contains("not supported for changes").contains("Nothing was changed");
    }
}
