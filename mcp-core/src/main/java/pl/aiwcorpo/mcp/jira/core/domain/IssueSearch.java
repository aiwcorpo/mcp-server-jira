package pl.aiwcorpo.mcp.jira.core.domain;

/**
 * What {@code search_issues} asks Jira. The model never supplies JQL: the tool layer builds it from validated
 * filter values with the structured query builder of the starter, which also adds the narrowing of a virtual
 * server link.
 *
 * @param projectKey the one project the search is limited to
 * @param jql        the complete query, built by the server
 */
public record IssueSearch(String projectKey, String jql) {
}
