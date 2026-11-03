package pl.aiwcorpo.mcp.jira.core.domain;

/**
 * The Jira behind the server reports a version this build was not tested with, so it only serves reads. Carries no
 * text from Jira: the client sees a fixed {@code upstream_unavailable}.
 */
public class BackendDegradedException extends RuntimeException {

    public BackendDegradedException() {
        super("jira version not supported for changes", null, false, false);
    }
}
