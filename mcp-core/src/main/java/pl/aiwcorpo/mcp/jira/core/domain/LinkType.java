package pl.aiwcorpo.mcp.jira.core.domain;

/** An issue link type: {@code name} is what link_issues takes, {@code inward} and {@code outward} are the two sentences of the link. */
public record LinkType(String id, String name, String inward, String outward) {
}
