package pl.aiwcorpo.mcp.jira.core.domain;

import java.util.List;

/** One page of work log entries; {@code nextStartAt} as in {@link CommentPage}. */
public record WorklogPage(List<JiraWorklog> worklogs, Integer nextStartAt) {

    public WorklogPage {
        worklogs = List.copyOf(worklogs);
    }
}
