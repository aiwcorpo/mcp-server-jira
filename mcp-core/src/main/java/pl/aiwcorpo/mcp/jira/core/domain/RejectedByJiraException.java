package pl.aiwcorpo.mcp.jira.core.domain;

/**
 * Jira refused a request built from the caller's values (400), e.g. a status name the instance does not
 * know or a transition its screen does not allow without more fields. Carries only which operation failed:
 * Jira's error text names values and fields and stays out of the result.
 */
public class RejectedByJiraException extends RuntimeException {

    public enum Operation { SEARCH, TRANSITION, COMMENT, DELETE, CREATE, UPDATE, ASSIGN, LINK, WORKLOG, RESTRICTED_COMMENT, SPRINTS, WATCHER, UNLINK }

    private final Operation operation;

    public RejectedByJiraException(Operation operation) {
        super("rejected by jira: " + operation, null, false, false);
        this.operation = operation;
    }

    public Operation operation() {
        return operation;
    }
}
