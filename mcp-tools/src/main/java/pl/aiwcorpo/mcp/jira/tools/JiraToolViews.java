package pl.aiwcorpo.mcp.jira.tools;

import java.util.List;
import pl.aiwcorpo.mcp.jira.core.domain.IssueSearchResult;
import pl.aiwcorpo.mcp.jira.core.domain.IssueTransition;
import pl.aiwcorpo.mcp.jira.core.domain.JiraComment;
import pl.aiwcorpo.mcp.jira.core.domain.JiraIssue;

/**
 * What the tools return to the model. Text written by Jira users (summary, description) sits only under
 * {@code untrustedText}, so a client and a model can tell data from Jira apart from the structure of the
 * result; nothing else in a result is free text from Jira users.
 */
final class JiraToolViews {

    /** Longer descriptions are cut: the model gets the start and a flag, not a whole specification. */
    static final int MAX_DESCRIPTION = 10_000;

    private JiraToolViews() {
    }

    record IssueText(String summary, String description, boolean descriptionTruncated) {
    }

    record IssueView(String key, String project, String type, String status, String assignee, String updated,
            IssueText untrustedText) {

        static IssueView of(JiraIssue issue) {
            String description = issue.description();
            boolean truncated = description != null && description.length() > MAX_DESCRIPTION;
            return new IssueView(issue.key(), issue.projectKey(), issue.type(), issue.status(), issue.assignee(),
                    issue.updated(), new IssueText(issue.summary(),
                            truncated ? description.substring(0, MAX_DESCRIPTION) : description, truncated));
        }
    }

    record SummaryText(String summary) {
    }

    record IssueListItem(String key, String type, String status, String assignee, String updated,
            SummaryText untrustedText) {
    }

    record SearchView(String project, int total, int returned, List<IssueListItem> issues) {

        static SearchView of(String project, IssueSearchResult result) {
            List<IssueListItem> items = result.issues().stream()
                    .map(i -> new IssueListItem(i.key(), i.type(), i.status(), i.assignee(), i.updated(),
                            new SummaryText(i.summary())))
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

        static TransitionView of(String issueKey, IssueTransition transition) {
            return new TransitionView(issueKey, transition.name(), transition.toStatus());
        }
    }
}
