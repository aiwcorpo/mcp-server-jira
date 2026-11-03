package pl.aiwcorpo.mcp.jira.tools;

import java.util.function.Supplier;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import pl.aiwcorpo.mcp.jira.core.domain.BackendDegradedException;
import pl.aiwcorpo.mcp.jira.core.domain.ObservedVersions;
import pl.aiwcorpo.mcp.platform.capability.BackendCapabilities;
import pl.aiwcorpo.mcp.platform.capability.Capabilities;

/**
 * Keeps the tools that change Jira from running against a version nobody tested (fail closed, as in the Confluence
 * server):
 * <ul>
 *   <li>{@code SUPPORTED} with the feature {@code issue_write}: writes go through;</li>
 *   <li>{@code DEGRADED} (older than 9.12, 12.x and newer, Cloud): reads only, whatever the setting;</li>
 *   <li>before the first probe answered, and {@code UNAVAILABLE} (no answer, {@code serverInfo} blocked in front of
 *       Jira, no readable version): reads only, unless {@code jira.writes-on-unknown-version} is true
 *       ({@code MCP_JIRA_WRITES_ON_UNKNOWN_VERSION}), which opens writes in this state only;</li>
 *   <li>{@code UNAVAILABLE} after the probe once classified the backend as untested: reads only, whatever the setting
 *       ({@link ObservedVersions}).</li>
 * </ul>
 */
@Component
public class JiraWriteGate {

    static final String PROBE = "jira";
    static final String WRITE_FEATURE = "issue_write";

    private final Supplier<Capabilities> current;
    private final ObservedVersions observed;
    private final boolean writesOnUnknownVersion;

    @Autowired
    public JiraWriteGate(BackendCapabilities capabilities, ObservedVersions observed,
            @Value("${jira.writes-on-unknown-version:false}") boolean writesOnUnknownVersion) {
        this(() -> capabilities.of(PROBE), observed, writesOnUnknownVersion);
    }

    /** For tests: the starter's {@code BackendCapabilities} cannot be filled from outside its package. */
    JiraWriteGate(Supplier<Capabilities> current, ObservedVersions observed, boolean writesOnUnknownVersion) {
        this.current = current;
        this.observed = observed;
        this.writesOnUnknownVersion = writesOnUnknownVersion;
    }

    public void requireWritable() {
        Capabilities capabilities = current.get();
        boolean allowed = switch (capabilities.state()) {
            case SUPPORTED -> capabilities.supports(WRITE_FEATURE) && !observed.untestedSeen();
            case DEGRADED -> false;
            case UNAVAILABLE -> writesOnUnknownVersion && !observed.untestedSeen();
        };
        if (!allowed) {
            throw new BackendDegradedException();
        }
    }
}
