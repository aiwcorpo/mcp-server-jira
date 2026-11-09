package pl.aiwcorpo.mcp.jira.core.domain;

import java.util.List;

/** One page of boards; {@code nextStartAt} as in {@link CommentPage}. */
public record BoardPage(List<JiraBoard> boards, Integer nextStartAt) {

    public BoardPage {
        boards = List.copyOf(boards);
    }
}
