package pl.aiwcorpo.mcp.jira.core.domain;

/**
 * One issue, reduced to the fields the tools hand out. {@code key} and {@code projectKey} come from Jira's
 * answer, not from the requested key: a moved issue answers under its new key and project.
 *
 * @param assignee        username of the assignee, {@code null} when unassigned
 * @param updated         last update as Jira formats it (ISO 8601 with offset)
 * @param summary         text written by Jira users
 * @param description     text written by Jira users (wiki markup, never rendered HTML); {@code null} when empty
 * @param projectCategory category of the project the issue is in, {@code ""} when the project has none; {@code null}
 *                        until the resolver of the policy looked the project up (search results do not carry it)
 */
public record JiraIssue(String key, String projectKey, String type, String status, String assignee,
        String updated, String summary, String description, String projectCategory) {

    public JiraIssue withProjectCategory(String category) {
        return new JiraIssue(key, projectKey, type, status, assignee, updated, summary, description, category);
    }
}
