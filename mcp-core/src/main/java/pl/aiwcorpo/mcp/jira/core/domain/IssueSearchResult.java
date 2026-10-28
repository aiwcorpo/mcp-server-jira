package pl.aiwcorpo.mcp.jira.core.domain;

import java.util.List;

/**
 * The first page of a search, newest update first. There is no total: it would count issues the result filter
 * removes afterwards.
 */
public record IssueSearchResult(List<JiraIssue> issues) {

    public IssueSearchResult {
        issues = List.copyOf(issues);
    }
}
