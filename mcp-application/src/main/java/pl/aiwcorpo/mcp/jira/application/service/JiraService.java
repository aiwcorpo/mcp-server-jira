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
        // A total that still counts what was dropped would tell the model how many hits it may not see.
        int total = inProject.size() == found.issues().size() ? found.total() : inProject.size();
        return new IssueSearchResult(total, inProject);
    }

    /**
     * Both writing tools first ask Jira for the key it knows the issue under: an issue moved to another project
     * still answers to its old key, and the write must go to, and be reported under, the key that is really
     * written to. The visibility check of that read is the same one the write would meet.
     */
    @Override
    public JiraComment addComment(String issueKey, String body) {
        String key = canonicalKey(issueKey);
        return jira.addComment(key, CommentText.asLiteral(body)).orElseThrow(NotFoundOrDeniedException::new);
    }

    @Override
    public TransitionOutcome transitionIssue(String issueKey, String transition) {
        String key = canonicalKey(issueKey);
        List<IssueTransition> available = jira.transitions(key).orElseThrow(NotFoundOrDeniedException::new);
        IssueTransition chosen = choose(available, transition);
        if (!jira.transition(key, chosen.id())) {
            throw new NotFoundOrDeniedException();
        }
        return new TransitionOutcome(key, chosen);
    }

    private String canonicalKey(String issueKey) {
        return jira.canonicalIssueKey(issueKey).orElseThrow(NotFoundOrDeniedException::new);
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
            throw new AmbiguousTransitionException(byName.stream().map(IssueTransition::id).toList());
        }
        return byName.stream().findFirst().orElseThrow(TransitionNotAvailableException::new);
    }
}
