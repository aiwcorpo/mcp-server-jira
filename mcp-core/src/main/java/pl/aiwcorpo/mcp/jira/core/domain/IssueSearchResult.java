package pl.aiwcorpo.mcp.jira.core.domain;

import java.util.List;

/**
 * The first page of a search, newest update first. There is no total: it would count issues the result filter
 * removes afterwards. {@code hasMore} is true when the server may have left matching issues out (more matches than
 * the page holds, or an issue type filter applied to a full page of rows that could not be asked for it).
 */
public record IssueSearchResult(List<JiraIssue> issues, boolean hasMore) {

    public IssueSearchResult {
        issues = List.copyOf(issues);
    }

    public IssueSearchResult(List<JiraIssue> issues) {
        this(issues, false);
    }
}
