package pl.aiwcorpo.mcp.jira.core.domain;

/** A user a project can assign issues to: username and display name, no e-mail address. */
public record JiraUser(String name, String displayName, boolean active) {
}
