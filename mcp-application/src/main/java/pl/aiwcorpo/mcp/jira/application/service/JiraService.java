package pl.aiwcorpo.mcp.jira.application.service;

import java.util.List;
import java.util.Locale;
import java.util.Optional;
import pl.aiwcorpo.mcp.jira.core.domain.AmbiguousTransitionException;
import pl.aiwcorpo.mcp.jira.core.domain.CommentPage;
import pl.aiwcorpo.mcp.jira.core.domain.IssueChanges;
import pl.aiwcorpo.mcp.jira.core.domain.IssueSearch;
import pl.aiwcorpo.mcp.jira.core.domain.IssueSearchResult;
import pl.aiwcorpo.mcp.jira.core.domain.IssueTransition;
import pl.aiwcorpo.mcp.jira.core.domain.JiraAttachment;
import pl.aiwcorpo.mcp.jira.core.domain.JiraComment;
import pl.aiwcorpo.mcp.jira.core.domain.JiraIssue;
import pl.aiwcorpo.mcp.jira.core.domain.JiraUser;
import pl.aiwcorpo.mcp.jira.core.domain.JiraWorklog;
import pl.aiwcorpo.mcp.jira.core.domain.NewIssue;
import pl.aiwcorpo.mcp.jira.core.domain.NotFoundOrDeniedException;
import pl.aiwcorpo.mcp.jira.core.domain.ProjectList;
import pl.aiwcorpo.mcp.jira.core.domain.TransitionNotAvailableException;
import pl.aiwcorpo.mcp.jira.core.domain.TransitionOutcome;
import pl.aiwcorpo.mcp.jira.core.domain.WorklogPage;
import pl.aiwcorpo.mcp.jira.core.port.in.JiraUseCase;
import pl.aiwcorpo.mcp.jira.core.port.out.JiraResourcePort;

/** Use cases of the server. A plain class: mcp-app makes it a bean, so this module stays free of Spring. */
public class JiraService implements JiraUseCase {

    /** Rows of one page of a search, of comments and of work log entries; the model pages with a cursor. */
    public static final int PAGE_SIZE = 25;
    /** Most users one lookup returns; the model narrows the query instead of paging through a directory. */
    public static final int USER_LOOKUP_SIZE = 20;
    /** Most projects read from Jira's unpaged list; the answer says when there were more. */
    public static final int MAX_PROJECTS = 500;

    private final JiraResourcePort jira;

    public JiraService(JiraResourcePort jira) {
        this.jira = jira;
    }

    /**
     * The project of the search is a {@code @ResourceRef}, so the policy already judged it and Jira showed it to the
     * caller. The query names the project as its scope; dropping any other project's issue keeps a mis-built query from
     * leaking them. The issue type cannot be in the query, so the rows of the page are limited to it here (a page can
     * come back short); the result filter of the starter then judges every remaining hit.
     */
    @Override
    public IssueSearchResult searchIssues(IssueSearch search) {
        IssueSearchResult found = jira.search(search, PAGE_SIZE);
        List<JiraIssue> matching = found.issues().stream()
                .filter(issue -> search.projectKey().equals(issue.projectKey()))
                .filter(issue -> search.issueType() == null || search.issueType().equalsIgnoreCase(issue.type()))
                .toList();
        return new IssueSearchResult(matching, found.nextStartAt());
    }

    @Override
    public ProjectList listProjects() {
        return jira.listProjects(MAX_PROJECTS);
    }

    @Override
    public List<JiraUser> assignableUsers(String projectKey, String query) {
        return jira.assignableUsers(projectKey, query, USER_LOOKUP_SIZE).orElseThrow(NotFoundOrDeniedException::new);
    }

    @Override
    public CommentPage listComments(String issueKey, int startAt) {
        return jira.listComments(issueKey, startAt, PAGE_SIZE).orElseThrow(NotFoundOrDeniedException::new);
    }

    @Override
    public JiraComment addComment(String issueKey, String body) {
        return jira.addComment(issueKey, CommentText.asLiteral(body)).orElseThrow(NotFoundOrDeniedException::new);
    }

    @Override
    public JiraComment editComment(String issueKey, String commentId, String body) {
        return jira.editComment(issueKey, commentId, CommentText.asLiteral(body))
                .orElseThrow(NotFoundOrDeniedException::new);
    }

    @Override
    public List<IssueTransition> listTransitions(String issueKey) {
        return jira.transitions(issueKey).orElseThrow(NotFoundOrDeniedException::new);
    }

    @Override
    public TransitionOutcome transitionIssue(String issueKey, String transition) {
        List<IssueTransition> available = jira.transitions(issueKey).orElseThrow(NotFoundOrDeniedException::new);
        IssueTransition chosen = choose(available, transition);
        if (!jira.transition(issueKey, chosen.id())) {
            throw new NotFoundOrDeniedException();
        }
        return new TransitionOutcome(issueKey, chosen);
    }

    @Override
    public void deleteIssue(String issueKey) {
        if (!jira.deleteIssue(issueKey)) {
            throw new NotFoundOrDeniedException();
        }
    }

    @Override
    public String createIssue(NewIssue issue) {
        NewIssue literal = new NewIssue(issue.projectKey(), issue.issueType(), issue.summary(),
                issue.description() == null ? null : CommentText.asLiteral(issue.description()), issue.labels(),
                issue.priority(), issue.assignee());
        return jira.createIssue(literal).orElseThrow(NotFoundOrDeniedException::new);
    }

    @Override
    public void updateIssue(String issueKey, IssueChanges changes) {
        IssueChanges literal = new IssueChanges(changes.summary(),
                changes.description() == null ? null : CommentText.asLiteral(changes.description()), changes.labels(),
                changes.priority());
        if (!jira.updateIssue(issueKey, literal)) {
            throw new NotFoundOrDeniedException();
        }
    }

    @Override
    public void assign(String issueKey, String username) {
        if (!jira.assign(issueKey, username)) {
            throw new NotFoundOrDeniedException();
        }
    }

    @Override
    public void linkIssues(String linkType, String inwardIssueKey, String outwardIssueKey) {
        if (!jira.linkIssues(linkType, inwardIssueKey, outwardIssueKey)) {
            throw new NotFoundOrDeniedException();
        }
    }

    @Override
    public JiraWorklog addWorklog(String issueKey, String timeSpent, String comment) {
        return jira.addWorklog(issueKey, timeSpent, comment == null ? null : CommentText.asLiteral(comment))
                .orElseThrow(NotFoundOrDeniedException::new);
    }

    @Override
    public WorklogPage listWorklogs(String issueKey, int startAt) {
        return jira.listWorklogs(issueKey, startAt, PAGE_SIZE).orElseThrow(NotFoundOrDeniedException::new);
    }

    @Override
    public List<JiraAttachment> listAttachments(String issueKey) {
        return jira.listAttachments(issueKey).orElseThrow(NotFoundOrDeniedException::new);
    }

    /**
     * An id wins over a name, a name must match whole (ignoring case) and exactly one transition. Jira lets
     * several transitions leaving one status share a name, and picking the first would move the issue somewhere
     * the caller did not name.
     */
    private static IssueTransition choose(List<IssueTransition> available, String requested) {
        Optional<IssueTransition> byId = available.stream().filter(t -> t.id().equals(requested)).findFirst();
        if (byId.isPresent()) {
            return byId.get();
        }
        String wanted = requested.toLowerCase(Locale.ROOT);
        List<IssueTransition> byName = available.stream()
                .filter(t -> t.name().toLowerCase(Locale.ROOT).equals(wanted))
                .toList();
        if (byName.size() > 1) {
            throw new AmbiguousTransitionException(byName);
        }
        return byName.stream().findFirst().orElseThrow(TransitionNotAvailableException::new);
    }
}
