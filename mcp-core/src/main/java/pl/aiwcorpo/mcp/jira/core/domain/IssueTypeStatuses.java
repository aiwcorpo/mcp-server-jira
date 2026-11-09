package pl.aiwcorpo.mcp.jira.core.domain;

import java.util.List;

/** The statuses of the workflow one issue type of a project uses; all names are set by Jira administrators. */
public record IssueTypeStatuses(String issueType, List<Status> statuses) {

    /** {@code category} is the name of the status category ("To Do", "In Progress", "Done"), {@code null} when absent. */
    public record Status(String name, String category) {
    }

    public IssueTypeStatuses {
        statuses = List.copyOf(statuses);
    }
}
