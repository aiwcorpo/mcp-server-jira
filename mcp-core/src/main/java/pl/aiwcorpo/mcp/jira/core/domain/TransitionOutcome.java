package pl.aiwcorpo.mcp.jira.core.domain;

/**
 * A transition that was carried out.
 *
 * @param issueKey   key Jira knows the issue under now; differs from the requested key when the issue was moved
 * @param transition the transition used
 */
public record TransitionOutcome(String issueKey, IssueTransition transition) {
}
