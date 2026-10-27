package pl.aiwcorpo.mcp.jira.core.domain;

/**
 * The issue or project does not exist or the caller may not see it. The two cases are deliberately one
 * exception: the client gets the same {@code not_found_or_denied} for both, so it cannot probe which exist.
 */
public class NotFoundOrDeniedException extends RuntimeException {

    public NotFoundOrDeniedException() {
        super("not found or not visible", null, false, false);
    }
}
