package pl.aiwcorpo.mcp.jira.core.domain;

/**
 * What {@code search_issues} looks for. Only these fields exist: the model never supplies JQL, the adapter
 * builds it from validated values. Every field except {@code projectKey} is optional ({@code null}).
 *
 * @param projectKey the one project the search is limited to
 * @param status     status name
 * @param assignee   assignee username
 * @param type       issue type name
 * @param text       words searched in summary, description and comments
 */
public record IssueSearch(String projectKey, String status, String assignee, String type, String text) {
}
