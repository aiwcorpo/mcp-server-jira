package pl.aiwcorpo.mcp.jira.core.domain;

/**
 * A workflow transition available on an issue.
 *
 * @param id       transition id
 * @param name     transition name shown in Jira
 * @param toStatus status the issue ends in
 */
public record IssueTransition(String id, String name, String toStatus) {
}
