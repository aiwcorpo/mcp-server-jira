package pl.aiwcorpo.mcp.jira.application.service;

import java.util.List;
import java.util.Locale;
import java.util.Optional;
import pl.aiwcorpo.mcp.jira.core.domain.AmbiguousTransitionException;
import pl.aiwcorpo.mcp.jira.core.domain.IssueSearch;
import pl.aiwcorpo.mcp.jira.core.domain.IssueSearchResult;
import pl.aiwcorpo.mcp.jira.core.domain.IssueTransition;
import pl.aiwcorpo.mcp.jira.core.domain.JiraComment;
import pl.aiwcorpo.mcp.jira.core.domain.NotFoundOrDeniedException;
import pl.aiwcorpo.mcp.jira.core.domain.TransitionNotAvailableException;
import pl.aiwcorpo.mcp.jira.core.domain.TransitionOutcome;
import pl.aiwcorpo.mcp.jira.core.port.in.JiraUseCase;
import pl.aiwcorpo.mcp.jira.core.port.out.JiraResourcePort;

/** Use cases of the server. A plain class: mcp-app makes it a bean, so this module stays free of Spring. */
public class JiraService implements JiraUseCase {

    /** One page only; the model narrows the search instead of paging through a project. */
    static final int MAX_SEARCH_RESULTS = 25;

    private final JiraResourcePort jira;

    public JiraService(JiraResourcePort jira) {
        this.jira = jira;
    }

    /**
     * The project of the search is a {@code @ResourceRef}, so the policy already judged it and Jira showed it to the
     * caller. The JQL names the project; dropping any other project's issue keeps a mis-built query from leaking
     * them, and the result filter of the starter then judges every remaining hit.
     */
    @Override
    public IssueSearchResult searchIssues(IssueSearch search) {
        IssueSearchResult found = jira.search(search, MAX_SEARCH_RESULTS);
        return new IssueSearchResult(found.issues().stream()
                .filter(issue -> search.projectKey().equals(issue.projectKey()))
                .toList());
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
