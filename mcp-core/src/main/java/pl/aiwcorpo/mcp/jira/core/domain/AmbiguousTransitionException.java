package pl.aiwcorpo.mcp.jira.core.domain;

import java.util.List;

/**
 * The name given matches several transitions of the issue (Jira does not keep names unique among the
 * transitions leaving one status). Which one was meant is not a guess the server makes: the caller repeats the
 * call with one of {@link #transitionIds()}.
 */
public class AmbiguousTransitionException extends RuntimeException {

    private final List<String> transitionIds;

    public AmbiguousTransitionException(List<String> transitionIds) {
        super("transition name is ambiguous", null, false, false);
        this.transitionIds = List.copyOf(transitionIds);
    }

    public List<String> transitionIds() {
        return transitionIds;
    }
}
