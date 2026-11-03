package pl.aiwcorpo.mcp.jira.core.port.out;

import java.util.List;
import java.util.Optional;
import pl.aiwcorpo.mcp.jira.core.domain.CommentPage;
import pl.aiwcorpo.mcp.jira.core.domain.IssueChanges;
import pl.aiwcorpo.mcp.jira.core.domain.IssueSearch;
import pl.aiwcorpo.mcp.jira.core.domain.IssueSearchResult;
import pl.aiwcorpo.mcp.jira.core.domain.IssueTransition;
import pl.aiwcorpo.mcp.jira.core.domain.JiraAttachment;
import pl.aiwcorpo.mcp.jira.core.domain.JiraComment;
import pl.aiwcorpo.mcp.jira.core.domain.JiraIssue;
import pl.aiwcorpo.mcp.jira.core.domain.JiraProject;
import pl.aiwcorpo.mcp.jira.core.domain.JiraUser;
import pl.aiwcorpo.mcp.jira.core.domain.JiraWorklog;
import pl.aiwcorpo.mcp.jira.core.domain.NewIssue;
import pl.aiwcorpo.mcp.jira.core.domain.ProjectList;
import pl.aiwcorpo.mcp.jira.core.domain.ServerInfo;
import pl.aiwcorpo.mcp.jira.core.domain.WorklogPage;

/**
 * Outbound port to Jira, implemented in mcp-integration, always as the calling user (except {@link #serverInfo()}).
 * "Empty" or {@code false} means Jira does not show the resource to the caller, whether it exists or not.
 */
public interface JiraResourcePort {

    /** Version and deployment type, asked without any credential; used by the capability probe only. */
    ServerInfo serverInfo();

    Optional<JiraProject> findProject(String projectKey);

    /** The issue under the key Jira knows it by now: a moved issue answers with its new key and project. */
    Optional<JiraIssue> findIssue(String issueKey);

    /** One page of issues of {@code search.projectKey()} matching the search, at most {@code maxResults} rows. */
    IssueSearchResult search(IssueSearch search, int maxResults);

    /** The first {@code limit} projects the caller may browse (Jira does not page this list; the rest is not read). */
    ProjectList listProjects(int limit);

    /** Users who can be assigned issues of the project whose name starts with or contains {@code query}. */
    Optional<List<JiraUser>> assignableUsers(String projectKey, String query, int maxResults);

    Optional<CommentPage> listComments(String issueKey, int startAt, int maxResults);

    /** Empty when the issue is not visible to the caller. */
    Optional<JiraComment> addComment(String issueKey, String body);

    /** Empty when the issue or the comment is not visible to the caller. */
    Optional<JiraComment> editComment(String issueKey, String commentId, String body);

    /** Empty when the issue is not visible to the caller. */
    Optional<List<IssueTransition>> transitions(String issueKey);

    /** @return false when the issue is not visible to the caller */
    boolean transition(String issueKey, String transitionId);

    /** @return false when the issue is not visible to the caller; true when Jira deleted it */
    boolean deleteIssue(String issueKey);

    /** @return the key of the new issue; empty when the project is not visible to the caller */
    Optional<String> createIssue(NewIssue issue);

    /** @return false when the issue is not visible to the caller */
    boolean updateIssue(String issueKey, IssueChanges changes);

    /** @param username {@code null} unassigns; @return false when the issue is not visible to the caller */
    boolean assign(String issueKey, String username);

    /** @return false when one of the issues or the link type is not visible to the caller */
    boolean linkIssues(String linkType, String inwardIssueKey, String outwardIssueKey);

    Optional<JiraWorklog> addWorklog(String issueKey, String timeSpent, String comment);

    Optional<WorklogPage> listWorklogs(String issueKey, int startAt, int maxResults);

    Optional<List<JiraAttachment>> listAttachments(String issueKey);
}
