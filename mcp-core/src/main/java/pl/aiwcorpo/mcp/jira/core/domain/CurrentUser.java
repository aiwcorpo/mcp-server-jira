package pl.aiwcorpo.mcp.jira.core.domain;

/** The Jira user behind the caller's credential: username, user key and display name; no e-mail address. */
public record CurrentUser(String name, String key, String displayName, boolean active) {
}
