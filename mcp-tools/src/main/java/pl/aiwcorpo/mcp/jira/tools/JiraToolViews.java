package pl.aiwcorpo.mcp.jira.tools;

import java.util.List;
import pl.aiwcorpo.mcp.jira.core.domain.IssueSearchResult;
import pl.aiwcorpo.mcp.jira.core.domain.IssueTransition;
import pl.aiwcorpo.mcp.jira.core.domain.JiraComment;
import pl.aiwcorpo.mcp.jira.core.domain.JiraIssue;
import pl.aiwcorpo.mcp.jira.core.domain.TransitionOutcome;

/**
 * What the tools return to the model. Text written by Jira users (summary, description) is an
 * {@link UntrustedText} (CC-072), so a client and a model can tell data from Jira apart from the structure of
 * the result. Names kept by Jira administrators (type, status, transition) stay plain fields, but are cut to
 * {@link #MAX_NAME} characters without control characters; nothing else in a result is free text from Jira.
 */
final class JiraToolViews {

    /** Longer descriptions are cut: the model gets the start and a flag, not a whole specification. */
    static final int MAX_DESCRIPTION = 10_000;
    static final int MAX_SUMMARY = 1_000;
    static final int MAX_NAME = 60;

    private JiraToolViews() {
    }

    /** Text of Jira users as data: {@code kind}, {@code format} and {@code truncated} as in C10/C6 CC-072. */
    record UntrustedText(String kind, String format, String text, boolean truncated) {

        static UntrustedText plain(String text, int maxLength) {
            if (text == null) {
                return null;
            }
            boolean truncated = text.length() > maxLength;
            return new UntrustedText("untrusted-data", "text/plain",
                    truncated ? text.substring(0, maxLength) : text, truncated);
        }
    }

    record IssueView(String key, String project, String type, String status, String assignee, String updated,
            UntrustedText summary, UntrustedText description) {

        static IssueView of(JiraIssue issue) {
            return new IssueView(issue.key(), issue.projectKey(), name(issue.type()), name(issue.status()),
                    issue.assignee(), issue.updated(), UntrustedText.plain(issue.summary(), MAX_SUMMARY),
                    UntrustedText.plain(issue.description(), MAX_DESCRIPTION));
        }
    }

    record IssueListItem(String key, String type, String status, String assignee, String updated,
            UntrustedText summary) {
    }

    record SearchView(String project, int total, int returned, List<IssueListItem> issues) {

        static SearchView of(String project, IssueSearchResult result) {
            List<IssueListItem> items = result.issues().stream()
                    .map(i -> new IssueListItem(i.key(), name(i.type()), name(i.status()), i.assignee(), i.updated(),
                            UntrustedText.plain(i.summary(), MAX_SUMMARY)))
                    .toList();
            return new SearchView(project, result.total(), items.size(), items);
        }
    }

    record CommentView(String issueKey, String commentId, String created) {

        static CommentView of(JiraComment comment) {
            return new CommentView(comment.issueKey(), comment.id(), comment.created());
        }
    }

    record TransitionView(String issueKey, String transition, String status) {

        static TransitionView of(TransitionOutcome outcome) {
            return new TransitionView(outcome.issueKey(), name(outcome.transition().name()),
                    name(outcome.transition().toStatus()));
        }
    }

    static String name(String name) {
        if (name == null) {
            return null;
        }
        String clean = name.replaceAll("\\p{Cntrl}|\\p{Cf}", "");
        return clean.length() > MAX_NAME ? clean.substring(0, MAX_NAME) : clean;
    }
}
