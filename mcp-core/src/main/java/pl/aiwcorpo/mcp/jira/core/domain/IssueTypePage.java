package pl.aiwcorpo.mcp.jira.core.domain;

import java.util.List;

/** One page of issue types; {@code nextStartAt} as in {@link CommentPage}. */
public record IssueTypePage(List<IssueTypeInfo> issueTypes, Integer nextStartAt) {

    public IssueTypePage {
        issueTypes = List.copyOf(issueTypes);
    }
}
