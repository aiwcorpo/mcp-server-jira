package pl.aiwcorpo.mcp.jira.core.domain;

/** A Jira Software board; {@code projectKey} is the project the board is located in, {@code null} when Jira names none. */
public record JiraBoard(String id, String name, String type, String projectKey) {
}
