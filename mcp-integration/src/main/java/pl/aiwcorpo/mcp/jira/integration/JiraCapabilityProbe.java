package pl.aiwcorpo.mcp.jira.integration;

import java.util.Locale;
import java.util.Set;
import org.springframework.stereotype.Component;
import pl.aiwcorpo.mcp.jira.core.domain.ObservedVersions;
import pl.aiwcorpo.mcp.jira.core.domain.ServerInfo;
import pl.aiwcorpo.mcp.jira.core.port.out.JiraResourcePort;
import pl.aiwcorpo.mcp.platform.capability.Capabilities;
import pl.aiwcorpo.mcp.platform.capability.CapabilityProbe;
import pl.aiwcorpo.mcp.platform.capability.ProductVersion;

/**
 * Reads {@code serverInfo} and decides which Jira this server may change.
 *
 * <p>Supported is Jira Data Center from {@value #MIN_MAJOR}.{@value #MIN_MINOR} up to the last major this build was
 * written against ({@value #MAX_MAJOR}.x). The REST API v2 this server uses ({@code /rest/api/2/...}, PAT as
 * {@code Bearer}, the offset-paged {@code POST /rest/api/2/search}) is the same across that range; what Atlassian
 * added in 10.4 (the "new search API") replaces the Lucene Java API for apps and leaves the REST search as it was.
 * Anything else is {@code DEGRADED}: older than 9.12 (out of support at Atlassian, PATs from 8.14 only), a major
 * newer than tested, or Jira Cloud (no {@code /rest/api/2/search} there, a different credential). In that state the
 * server still reads and refuses every change with {@code upstream_unavailable}, instead of guessing. A failing or
 * unreadable {@code serverInfo} after such a finding keeps reporting it ({@link ObservedVersions}): one bad run of the
 * probe does not reopen writes.
 *
 * <p>The version is untrusted text from the backend: it is only parsed to numbers here, and the starter cleans what it
 * logs.
 */
@Component
public class JiraCapabilityProbe implements CapabilityProbe {

    public static final String NAME = "jira";

    /** Reads that work on every Jira that answers REST API v2. */
    public static final String ISSUE_READ = "issue_read";
    /** Create, update, comment, transition, delete; granted only for a tested version. */
    public static final String ISSUE_WRITE = "issue_write";
    /** {@code POST /rest/api/2/search} with {@code startAt} paging. */
    public static final String SEARCH_OFFSET = "search_offset";

    static final int MIN_MAJOR = 9;
    static final int MIN_MINOR = 12;
    static final int MAX_MAJOR = 11;

    private final JiraResourcePort jira;
    private final ObservedVersions observed;
    private volatile Capabilities lastUntested;

    public JiraCapabilityProbe(JiraResourcePort jira, ObservedVersions observed) {
        this.jira = jira;
        this.observed = observed;
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public Capabilities probe() {
        Capabilities found;
        try {
            found = classify(jira.serverInfo());
        } catch (RuntimeException failed) {
            Capabilities untested = lastUntested;
            if (untested != null) {
                return untested;
            }
            throw failed;
        }
        switch (found.state()) {
            case SUPPORTED -> {
                lastUntested = null;
                observed.tested();
            }
            case DEGRADED -> {
                lastUntested = found;
                observed.untested();
            }
            case UNAVAILABLE -> {
                if (lastUntested != null) {
                    return lastUntested;
                }
            }
        }
        return found;
    }

    static Capabilities classify(ServerInfo info) {
        ProductVersion version = ProductVersion.parse(info.version()).orElse(null);
        if (version == null) {
            return Capabilities.unavailable("serverInfo has no version");
        }
        if (info.deploymentType() != null && "cloud".equals(info.deploymentType().toLowerCase(Locale.ROOT))) {
            return Capabilities.degraded(version.toString(), Set.of(ISSUE_READ), "Jira Cloud is not supported");
        }
        if (version.before(MIN_MAJOR, MIN_MINOR)) {
            return Capabilities.degraded(version.toString(), Set.of(ISSUE_READ), "older than 9.12");
        }
        if (version.major() > MAX_MAJOR) {
            return Capabilities.degraded(version.toString(), Set.of(ISSUE_READ), "newer than tested (11.x)");
        }
        return Capabilities.supported(version.toString(), Set.of(ISSUE_READ, ISSUE_WRITE, SEARCH_OFFSET));
    }
}
