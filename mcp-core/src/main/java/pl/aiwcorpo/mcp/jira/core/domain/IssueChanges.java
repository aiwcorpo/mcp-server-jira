package pl.aiwcorpo.mcp.jira.core.domain;

import java.util.List;

/**
 * The fields {@code update_issue} may set; each is {@code null} when it is left alone. {@code labels} replaces the
 * whole set (an empty list clears it), so repeating the call leaves the same state.
 */
public record IssueChanges(String summary, String description, List<String> labels, String priority) {

    public IssueChanges {
        labels = labels == null ? null : List.copyOf(labels);
    }

    public boolean isEmpty() {
        return summary == null && description == null && labels == null && priority == null;
    }
}
