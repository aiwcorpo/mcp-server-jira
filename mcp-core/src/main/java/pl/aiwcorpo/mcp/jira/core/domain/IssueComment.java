package pl.aiwcorpo.mcp.jira.core.domain;

/**
 * A comment as listed: text written by Jira users in wiki markup, never rendered HTML.
 *
 * @param author username of the author, {@code null} when Jira does not say
 */
public record IssueComment(String id, String author, String created, String updated, String body) {
}
