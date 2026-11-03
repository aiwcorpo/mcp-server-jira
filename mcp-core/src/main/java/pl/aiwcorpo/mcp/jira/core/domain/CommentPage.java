package pl.aiwcorpo.mcp.jira.core.domain;

import java.util.List;

/** One page of comments, oldest first. {@code nextStartAt} is the offset of the next page, {@code null} on the last. */
public record CommentPage(List<IssueComment> comments, Integer nextStartAt) {

    public CommentPage {
        comments = List.copyOf(comments);
    }
}
