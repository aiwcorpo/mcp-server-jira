package pl.aiwcorpo.mcp.jira.core.domain;

/** Metadata of an attachment; the content is never fetched. {@code size} is in bytes, {@code -1} when Jira omits it. */
public record JiraAttachment(String id, String filename, long size, String mimeType, String created, String author) {
}
