package pl.aiwcorpo.mcp.jira.core.domain;

/** An issue type a project offers for creating issues; {@code id} is what the create metadata of the type is asked by. */
public record IssueTypeInfo(String id, String name, boolean subtask) {
}
