package pl.aiwcorpo.mcp.jira.core.domain;

/** The issue has no transition with the requested id or name in its current status, for this caller. */
public class TransitionNotAvailableException extends RuntimeException {

    public TransitionNotAvailableException() {
        super("transition not available", null, false, false);
    }
}
