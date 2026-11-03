package pl.aiwcorpo.mcp.jira.core.domain;

/** A project in the list of projects the caller may browse; {@code category} is {@code ""} when it has none. */
public record ProjectSummary(String key, String name, String category) {
}
