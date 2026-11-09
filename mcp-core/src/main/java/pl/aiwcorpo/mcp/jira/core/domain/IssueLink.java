package pl.aiwcorpo.mcp.jira.core.domain;

/**
 * A link of an issue to another one, seen from the issue that carries it.
 *
 * @param typeName    link type name, as link_issues takes it
 * @param direction   {@code inward} or {@code outward}: which sentence of the type describes this issue
 * @param description that sentence ("blocks", "is blocked by")
 * @param otherKey    key of the linked issue
 */
public record IssueLink(String id, String typeName, String direction, String description, String otherKey) {
}
