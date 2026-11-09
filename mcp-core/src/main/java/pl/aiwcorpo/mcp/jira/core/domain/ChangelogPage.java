package pl.aiwcorpo.mcp.jira.core.domain;

import java.util.List;

/**
 * One page of an issue's change history, oldest first; {@code nextStartAt} as in {@link CommentPage}. Entries up to and
 * including the last change of the project field are not on a page. {@code historyIncomplete} is true when Jira returned
 * less history than it holds (or does not say), so an older move to another project may be in the part not seen.
 */
public record ChangelogPage(List<ChangelogEntry> entries, Integer nextStartAt, boolean historyIncomplete) {

    public ChangelogPage {
        entries = List.copyOf(entries);
    }
}
