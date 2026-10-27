package pl.aiwcorpo.mcp.jira.core.domain;

/** A comment Jira created: its id and creation time, not its body, which the caller already has. */
public record JiraComment(String issueKey, String id, String created) {
}
