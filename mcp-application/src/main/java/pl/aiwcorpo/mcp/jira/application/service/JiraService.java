package pl.aiwcorpo.mcp.jira.application.service;

import java.util.List;
import java.util.Locale;
import java.util.Optional;
import pl.aiwcorpo.mcp.jira.core.domain.AmbiguousTransitionException;
import pl.aiwcorpo.mcp.jira.core.domain.IssueSearch;
import pl.aiwcorpo.mcp.jira.core.domain.IssueSearchResult;
import pl.aiwcorpo.mcp.jira.core.domain.IssueTransition;
import pl.aiwcorpo.mcp.jira.core.domain.JiraComment;
import pl.aiwcorpo.mcp.jira.core.domain.JiraIssue;
import pl.aiwcorpo.mcp.jira.core.domain.NotFoundOrDeniedException;
import pl.aiwcorpo.mcp.jira.core.domain.TransitionNotAvailableException;
import pl.aiwcorpo.mcp.jira.core.domain.TransitionOutcome;
import pl.aiwcorpo.mcp.jira.core.port.in.JiraUseCase;
import pl.aiwcorpo.mcp.jira.core.port.out.JiraResourcePort;

/** Use cases of the server. A plain class: mcp-app makes it a bean, so this module stays free of Spring. */
public class JiraService implements JiraUseCase {

    /**
     * Rows asked from Jira before the issue type is applied to them: the type is not a field a query may use, so a page
     * of the result size could hold none of the requested type.
     */
    static final int SEARCH_FETCH_SIZE = 100;

    private final JiraResourcePort jira;

    public JiraService(JiraResourcePort jira) {
        this.jira = jira;
    }

    /**
     * The project of the search is a {@code @ResourceRef}, so the policy already judged it and Jira showed it to the
     * caller. The query names the project as its scope; dropping any other project's issue keeps a mis-built query from
     * leaking them. The issue type cannot be in the query, so the rows are limited to it here; the result filter of the starter then judges every
     * remaining hit.
     */
    @Override
    public IssueSearchResult searchIssues(IssueSearch search) {
        IssueSearchResult found = jira.search(search, SEARCH_FETCH_SIZE);
        List<JiraIssue> matching = found.issues().stream()
                .filter(issue -> search.projectKey().equals(issue.projectKey()))
                .filter(issue -> search.issueType() == null || search.issueType().equalsIgnoreCase(issue.type()))
                .toList();
        // Only what Jira reports about the page itself: the number of matches says nothing a caller may rely on, because
        // some of them may be hidden from the caller by the policy, which is judged afterwards (the tool cuts the list
        // to the visible hits and sets hasMore from that).
        boolean typeFilterOnFullPage = search.issueType() != null && found.issues().size() >= SEARCH_FETCH_SIZE;
        return new IssueSearchResult(matching, typeFilterOnFullPage);
    }

    @Override
    public JiraComment addComment(String issueKey, String body) {
        return jira.addComment(issueKey, CommentText.asLiteral(body)).orElseThrow(NotFoundOrDeniedException::new);
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
