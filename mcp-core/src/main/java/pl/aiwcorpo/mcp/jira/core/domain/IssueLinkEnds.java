package pl.aiwcorpo.mcp.jira.core.domain;

/** The two issues one issue link joins, as keys Jira reports now. */
public record IssueLinkEnds(String id, String inwardIssueKey, String outwardIssueKey) {
}
