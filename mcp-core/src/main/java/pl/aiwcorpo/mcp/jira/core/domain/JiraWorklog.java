package pl.aiwcorpo.mcp.jira.core.domain;

/** A work log entry; {@code comment} is wiki markup written by Jira users and may be {@code null}. */
public record JiraWorklog(String id, String author, String started, String timeSpent, String comment) {
}
