package pl.aiwcorpo.mcp.jira.integration;

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
import pl.aiwcorpo.mcp.jira.core.port.out.JiraResourcePort;

/** A port that fails every call, so a test overrides exactly the calls the unit under test may make. */
abstract class UnsupportedJira implements JiraResourcePort {

    @Override
    public ServerInfo serverInfo() {
        throw new UnsupportedOperationException();
    }

    @Override
    public Optional<JiraProject> findProject(String projectKey) {
        throw new UnsupportedOperationException();
    }

    @Override
    public Optional<JiraIssue> findIssue(String issueKey) {
        throw new UnsupportedOperationException();
    }

    @Override
    public IssueSearchResult search(IssueSearch search, int maxResults) {
        throw new UnsupportedOperationException();
    }

    @Override
    public ProjectList listProjects(int limit) {
        throw new UnsupportedOperationException();
    }

    @Override
    public Optional<List<JiraUser>> assignableUsers(String projectKey, String query, int maxResults) {
        throw new UnsupportedOperationException();
    }

    @Override
    public Optional<CommentPage> listComments(String issueKey, int startAt, int maxResults) {
        throw new UnsupportedOperationException();
    }

    @Override
    public Optional<JiraComment> addComment(String issueKey, String body) {
        throw new UnsupportedOperationException();
    }

    @Override
    public Optional<JiraComment> editComment(String issueKey, String commentId, String body) {
        throw new UnsupportedOperationException();
    }

    @Override
    public Optional<List<IssueTransition>> transitions(String issueKey) {
        throw new UnsupportedOperationException();
    }

    @Override
    public boolean transition(String issueKey, String transitionId) {
        throw new UnsupportedOperationException();
    }

    @Override
    public boolean deleteIssue(String issueKey) {
        throw new UnsupportedOperationException();
    }

    @Override
    public Optional<String> createIssue(NewIssue issue) {
        throw new UnsupportedOperationException();
    }

    @Override
    public boolean updateIssue(String issueKey, IssueChanges changes) {
        throw new UnsupportedOperationException();
    }

    @Override
    public boolean assign(String issueKey, String username) {
        throw new UnsupportedOperationException();
    }

    @Override
    public boolean linkIssues(String linkType, String inwardIssueKey, String outwardIssueKey) {
        throw new UnsupportedOperationException();
    }

    @Override
    public Optional<JiraWorklog> addWorklog(String issueKey, String timeSpent, String comment) {
        throw new UnsupportedOperationException();
    }

    @Override
    public Optional<WorklogPage> listWorklogs(String issueKey, int startAt, int maxResults) {
        throw new UnsupportedOperationException();
    }

    @Override
    public Optional<List<JiraAttachment>> listAttachments(String issueKey) {
        throw new UnsupportedOperationException();
    }
}
