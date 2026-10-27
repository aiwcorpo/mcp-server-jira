package pl.aiwcorpo.mcp.jira.core.domain;

import java.util.List;

/**
 * @param total  number of matching issues Jira reports, which may exceed the returned page
 * @param issues the first page, newest update first
 */
public record IssueSearchResult(int total, List<JiraIssue> issues) {

    public IssueSearchResult {
        issues = List.copyOf(issues);
    }
}
