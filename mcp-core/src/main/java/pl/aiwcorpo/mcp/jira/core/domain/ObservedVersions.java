package pl.aiwcorpo.mcp.jira.core.domain;

/**
 * What the version probe has seen so far, shared by the probe (writes) and the write gate (reads). The probe of the
 * starter replaces its state with every run, so one timeout or a login page would turn {@code degraded} into
 * {@code unavailable} and reopen writes to a version nobody tested. This memory keeps the finding: once a probe
 * classified the backend as untested it stays so until a probe classifies a tested version.
 */
public final class ObservedVersions {

    private volatile boolean untestedSeen;

    public void untested() {
        untestedSeen = true;
    }

    public void tested() {
        untestedSeen = false;
    }

    public boolean untestedSeen() {
        return untestedSeen;
    }
}
