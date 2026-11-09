package pl.aiwcorpo.mcp.jira.core.domain;

import java.util.List;

/**
 * One change of an issue by one author. {@code author} is the display name ({@code null} for a change without
 * an author); the field changes carry text written by Jira users.
 *
 * @param beforeProjectChange true when the issue changed project later than this entry; values of fields bound to a
 *                            project (workflow, versions, components, sprints) then describe another project
 */
public record ChangelogEntry(String id, String author, String created, List<FieldChange> changes,
        boolean beforeProjectChange) {

    /** {@code from} and {@code to} are the strings Jira shows for the old and the new value; either may be {@code null}. */
    public record FieldChange(String field, String from, String to) {
    }

    public ChangelogEntry {
        changes = List.copyOf(changes);
    }
}
