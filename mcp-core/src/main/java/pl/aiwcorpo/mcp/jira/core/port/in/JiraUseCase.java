package pl.aiwcorpo.mcp.jira.core.port.in;

import pl.aiwcorpo.mcp.jira.core.domain.IssueSearch;
import pl.aiwcorpo.mcp.jira.core.domain.IssueSearchResult;
import pl.aiwcorpo.mcp.jira.core.domain.IssueTransition;
import pl.aiwcorpo.mcp.jira.core.domain.JiraComment;
import pl.aiwcorpo.mcp.jira.core.domain.JiraIssue;
import pl.aiwcorpo.mcp.jira.core.domain.TransitionOutcome;

/**
 * Inbound port: what the server can do, in business terms. Implemented in mcp-application, called by
 * mcp-tools with arguments already validated. Issues and projects the caller's Jira account does not see
 * raise {@link pl.aiwcorpo.mcp.jira.core.domain.NotFoundOrDeniedException}.
 */
public interface JiraUseCase {

    JiraIssue getIssue(String issueKey);

    IssueSearchResult searchIssues(IssueSearch search);

    /**
     * Writes under the key Jira reports for the issue, not under the requested one, and answers with that key.
     *
     * @param body plain text; the use case makes sure Jira does not interpret it as wiki markup
     */
    JiraComment addComment(String issueKey, String body);

    /**
     * @param transition transition id (digits, wins over a name) or name, case-insensitive
     * @throws pl.aiwcorpo.mcp.jira.core.domain.TransitionNotAvailableException when the issue offers no such
     *         transition now
     * @throws pl.aiwcorpo.mcp.jira.core.domain.AmbiguousTransitionException when a name fits several transitions
     */
    TransitionOutcome transitionIssue(String issueKey, String transition);
}
