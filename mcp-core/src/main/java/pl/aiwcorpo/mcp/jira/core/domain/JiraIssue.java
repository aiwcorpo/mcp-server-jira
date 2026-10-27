package pl.aiwcorpo.mcp.jira.core.domain;

/**
 * One issue, reduced to the fields the tools hand out. {@code key} and {@code projectKey} come from Jira's
 * answer, not from the requested key: a moved issue answers under its new key and project.
 *
 * @param assignee    username of the assignee, {@code null} when unassigned
 * @param updated     last update as Jira formats it (ISO 8601 with offset)
 * @param summary     text written by Jira users
 * @param description text written by Jira users (wiki markup, never rendered HTML); {@code null} when empty
 */
public record JiraIssue(String key, String projectKey, String type, String status, String assignee,
        String updated, String summary, String description) {
}
