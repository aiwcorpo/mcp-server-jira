package pl.aiwcorpo.mcp.jira.application.service;

import java.util.List;
import java.util.Locale;
import pl.aiwcorpo.mcp.jira.core.domain.IssueSearch;
import pl.aiwcorpo.mcp.jira.core.domain.IssueSearchResult;
import pl.aiwcorpo.mcp.jira.core.domain.IssueTransition;
import pl.aiwcorpo.mcp.jira.core.domain.JiraComment;
import pl.aiwcorpo.mcp.jira.core.domain.JiraIssue;
import pl.aiwcorpo.mcp.jira.core.domain.NotFoundOrDeniedException;
import pl.aiwcorpo.mcp.jira.core.domain.TransitionNotAvailableException;
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

    @Override
    public JiraIssue getIssue(String issueKey) {
        return jira.findIssue(issueKey).orElseThrow(NotFoundOrDeniedException::new);
    }

    /**
     * The project is looked up first: Jira answers a JQL naming a project the caller cannot see with 400 and
     * the project name in the error, and this way the answer is the same {@code not_found_or_denied} as for
     * an issue. It is also where the resolver of {@code Jira::Project} (starter beta) gets its attributes.
     */
    @Override
    public IssueSearchResult searchIssues(IssueSearch search) {
        jira.findProject(search.projectKey()).orElseThrow(NotFoundOrDeniedException::new);
        IssueSearchResult found = jira.search(search, MAX_SEARCH_RESULTS);
        // The JQL already names the project; dropping anything else keeps a mis-built query from leaking
        // other projects. ResultFilter of the starter beta replaces this check.
        List<JiraIssue> inProject = found.issues().stream()
                .filter(issue -> search.projectKey().equals(issue.projectKey()))
                .toList();
        return new IssueSearchResult(found.total(), inProject);
    }

    @Override
    public JiraComment addComment(String issueKey, String body) {
        return jira.addComment(issueKey, body).orElseThrow(NotFoundOrDeniedException::new);
    }

    @Override
    public IssueTransition transitionIssue(String issueKey, String transition) {
        List<IssueTransition> available = jira.transitions(issueKey).orElseThrow(NotFoundOrDeniedException::new);
        IssueTransition chosen = available.stream()
                .filter(t -> t.id().equals(transition)
                        || t.name().toLowerCase(Locale.ROOT).equals(transition.toLowerCase(Locale.ROOT)))
                .findFirst()
                .orElseThrow(TransitionNotAvailableException::new);
        if (!jira.transition(issueKey, chosen.id())) {
            throw new NotFoundOrDeniedException();
        }
        return chosen;
    }
}
