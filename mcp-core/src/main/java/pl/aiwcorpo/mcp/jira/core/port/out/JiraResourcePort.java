package pl.aiwcorpo.mcp.jira.core.port.out;

import java.util.List;
import java.util.Optional;
import pl.aiwcorpo.mcp.jira.core.domain.IssueSearch;
import pl.aiwcorpo.mcp.jira.core.domain.IssueSearchResult;
import pl.aiwcorpo.mcp.jira.core.domain.IssueTransition;
import pl.aiwcorpo.mcp.jira.core.domain.JiraComment;
import pl.aiwcorpo.mcp.jira.core.domain.JiraIssue;
import pl.aiwcorpo.mcp.jira.core.domain.JiraProject;

/**
 * Outbound port to Jira, implemented in mcp-integration, always as the calling user. "Empty" means Jira does
 * not show the resource to the caller, whether it exists or not.
 */
public interface JiraResourcePort {

    Optional<JiraProject> findProject(String projectKey);

    /** The issue under the key Jira knows it by now: a moved issue answers with its new key and project. */
    Optional<JiraIssue> findIssue(String issueKey);

    /** Issues of {@code search.projectKey()} matching the search, at most {@code maxResults}. */
    IssueSearchResult search(IssueSearch search, int maxResults);

    /** Empty when the issue is not visible to the caller. */
    Optional<JiraComment> addComment(String issueKey, String body);

    /** Empty when the issue is not visible to the caller. */
    Optional<List<IssueTransition>> transitions(String issueKey);

    /** @return false when the issue is not visible to the caller */
    boolean transition(String issueKey, String transitionId);

    /** @return false when the issue is not visible to the caller; true when Jira deleted it */
    boolean deleteIssue(String issueKey);
}
