package pl.aiwcorpo.mcp.jira.tools;

import java.util.List;
import org.springframework.lang.Nullable;
import pl.aiwcorpo.mcp.jira.core.domain.IssueSearchResult;
import pl.aiwcorpo.mcp.jira.core.domain.IssueTransition;
import pl.aiwcorpo.mcp.jira.core.domain.JiraComment;
import pl.aiwcorpo.mcp.jira.core.domain.JiraIssue;
import pl.aiwcorpo.mcp.jira.core.domain.JiraResourceModel;
import pl.aiwcorpo.mcp.jira.core.domain.TransitionOutcome;
import pl.aiwcorpo.mcp.platform.resource.ResourceRef;
import pl.aiwcorpo.mcp.platform.text.TextHygiene;
import pl.aiwcorpo.mcp.platform.text.UntrustedText;

/**
 * What the tools return to the model. Text written by Jira users (summary, description) is an
 * {@link UntrustedText} (CC-072), so a client and a model can tell data from Jira apart from the structure of
 * the result. Names kept by Jira administrators (type, status, transition) stay plain fields, but are
 * normalized and cut to {@link #MAX_NAME} characters; nothing else in a result is free text from Jira. A field
 * Jira may leave empty is {@code @Nullable}; the components carry no Jackson annotations because the result
 * schema is built from their names.
 */
final class JiraToolViews {

    /** Longer descriptions are cut: the model gets the start and a flag, not a whole specification. */
    static final int MAX_DESCRIPTION = 10_000;
    static final int MAX_SUMMARY = 1_000;
    static final int MAX_NAME = 60;
    static final int MAX_USERNAME = 100;

    private JiraToolViews() {
    }

    record IssueView(String key, @Nullable String project, @Nullable String type, @Nullable String status,
            @Nullable String assignee, @Nullable String updated, UntrustedText summary, @Nullable UntrustedText description) {

        static IssueView of(JiraIssue issue) {
            return new IssueView(issue.key(), issue.projectKey(), name(issue.type()), name(issue.status()),
                    username(issue.assignee()), issue.updated(), summaryText(issue.summary()),
                    JiraWikiText.plain(issue.description(), MAX_DESCRIPTION));
        }
    }

    record IssueListItem(@ResourceRef(type = JiraResourceModel.ISSUE_TYPE) String key, @Nullable String type, @Nullable String status, @Nullable String assignee,
            @Nullable String updated, UntrustedText summary) {
    }

    record SearchView(String project, List<IssueListItem> issues, boolean hasMore) {

        static SearchView of(String project, IssueSearchResult result) {
            List<IssueListItem> items = result.issues().stream()
                    .map(i -> new IssueListItem(i.key(), name(i.type()), name(i.status()), username(i.assignee()), i.updated(),
                            summaryText(i.summary())))
                    .toList();
            return new SearchView(project, items, result.hasMore());
        }
    }

    record CommentView(String issueKey, String commentId, String created) {

        static CommentView of(JiraComment comment) {
            return new CommentView(comment.issueKey(), comment.id(), comment.created());
        }
    }

    record DeleteView(String issueKey, boolean deleted) {
    }

    record TransitionView(String issueKey, String transition, @Nullable String status) {

        static TransitionView of(TransitionOutcome outcome) {
            return new TransitionView(outcome.issueKey(), name(outcome.transition().name()),
                    name(outcome.transition().toStatus()));
        }
    }

    /** The summary is a single-line text field in Jira, not wiki markup: no markup filter, only hygiene and the cut. */
    static UntrustedText summaryText(String summary) {
        return UntrustedText.plain(summary == null ? "" : summary, MAX_SUMMARY);
    }

    /** User names come from the company directory, not from Jira alone; same hygiene as the names, longer limit. */
    static String username(String username) {
        return username == null ? null : UntrustedText.cut(TextHygiene.normalize(username), MAX_USERNAME);
    }

    static String name(String name) {
        return name == null ? null : UntrustedText.cut(TextHygiene.normalize(name), MAX_NAME);
    }
}
