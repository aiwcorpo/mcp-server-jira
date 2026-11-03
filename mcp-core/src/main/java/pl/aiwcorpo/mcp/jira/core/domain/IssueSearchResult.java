package pl.aiwcorpo.mcp.jira.core.domain;

import java.util.List;

/**
 * One page of a search. There is no total: it would count issues the result filter removes afterwards.
 * {@code nextStartAt} is where Jira's next page begins, {@code null} on the last page; a page may hold fewer rows
 * than Jira returned, because rows of another project or issue type are dropped.
 */
public record IssueSearchResult(List<JiraIssue> issues, Integer nextStartAt) {

    public IssueSearchResult {
        issues = List.copyOf(issues);
    }
}
