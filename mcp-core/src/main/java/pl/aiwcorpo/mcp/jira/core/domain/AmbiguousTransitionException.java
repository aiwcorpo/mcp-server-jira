package pl.aiwcorpo.mcp.jira.core.domain;

import java.util.List;

/**
 * The name given matches several transitions of the issue (Jira does not keep names unique among the
 * transitions leaving one status). Which one was meant is not a guess the server makes: the caller repeats the
 * call with the id of one of {@link #candidates()}, which also say where each of them leads.
 */
public class AmbiguousTransitionException extends RuntimeException {

    private final List<IssueTransition> candidates;

    public AmbiguousTransitionException(List<IssueTransition> candidates) {
        super("transition name is ambiguous", null, false, false);
        this.candidates = List.copyOf(candidates);
    }

    public List<IssueTransition> candidates() {
        return candidates;
    }

    public List<String> transitionIds() {
        return candidates.stream().map(IssueTransition::id).toList();
    }
}
