package pl.aiwcorpo.mcp.jira.core.domain;

import java.util.List;

/** One page of an issue's change history, oldest first; {@code nextStartAt} as in {@link CommentPage}. */
public record ChangelogPage(List<ChangelogEntry> entries, Integer nextStartAt) {

    public ChangelogPage {
        entries = List.copyOf(entries);
    }
}
