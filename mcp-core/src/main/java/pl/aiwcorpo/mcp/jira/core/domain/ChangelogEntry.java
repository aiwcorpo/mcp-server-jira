package pl.aiwcorpo.mcp.jira.core.domain;

import java.util.List;

/**
 * One change of an issue by one author. {@code author} is the display name ({@code null} for a change without
 * an author); the field changes carry text written by Jira users.
 */
public record ChangelogEntry(String id, String author, String created, List<FieldChange> changes) {

    /** {@code from} and {@code to} are the strings Jira shows for the old and the new value; either may be {@code null}. */
    public record FieldChange(String field, String from, String to) {
    }

    public ChangelogEntry {
        changes = List.copyOf(changes);
    }
}
