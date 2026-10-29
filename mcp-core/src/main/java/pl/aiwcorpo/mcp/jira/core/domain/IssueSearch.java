package pl.aiwcorpo.mcp.jira.core.domain;

/**
 * What {@code search_issues} asks Jira. The model never supplies JQL: the tool layer builds it from validated
 * filter values with the structured query builder of the starter, which also adds the narrowing of a virtual
 * server link.
 *
 * @param projectKey the one project the search is limited to; not a condition of the query (the project is the scope
 *                   of the query, which the contracts fix), so the use case applies it to the rows
 * @param issueType  the issue type the rows are limited to, {@code null} for any; applied to the rows for the same
 *                   reason (the type is not a field the contracts allow in a condition)
 * @param jql        the complete query, built by the server
 */
public record IssueSearch(String projectKey, String issueType, String jql) {
}
