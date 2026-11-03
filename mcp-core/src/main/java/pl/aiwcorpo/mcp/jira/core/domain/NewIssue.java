package pl.aiwcorpo.mcp.jira.core.domain;

import java.util.List;

/**
 * An issue to create. {@code description} is plain text to be stored literally (the use case wraps it);
 * optional fields are {@code null}, labels may be empty.
 */
public record NewIssue(String projectKey, String issueType, String summary, String description, List<String> labels,
        String priority, String assignee) {

    public NewIssue {
        labels = labels == null ? List.of() : List.copyOf(labels);
    }
}
