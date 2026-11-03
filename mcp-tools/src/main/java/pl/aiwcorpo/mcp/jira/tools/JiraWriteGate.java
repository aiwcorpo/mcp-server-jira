package pl.aiwcorpo.mcp.jira.tools;

import org.springframework.stereotype.Component;
import pl.aiwcorpo.mcp.jira.core.domain.BackendDegradedException;
import pl.aiwcorpo.mcp.platform.capability.BackendCapabilities;
import pl.aiwcorpo.mcp.platform.capability.Capabilities;

/**
 * Keeps the tools that change Jira from running against a version nobody tested. {@code DEGRADED} (a Jira older than
 * 9.12, newer than 11.x, or Cloud) serves reads only. {@code UNAVAILABLE} (not probed yet, or {@code serverInfo}
 * blocked in front of Jira) does not block: a Jira that hides its version is not evidence of an untested one, and
 * the writes use the same REST calls as before the probe existed.
 */
@Component
public class JiraWriteGate {

    static final String PROBE = "jira";

    private final BackendCapabilities capabilities;

    public JiraWriteGate(BackendCapabilities capabilities) {
        this.capabilities = capabilities;
    }

    public void requireWritable() {
        if (capabilities.of(PROBE).state() == Capabilities.State.DEGRADED) {
            throw new BackendDegradedException();
        }
    }
}
