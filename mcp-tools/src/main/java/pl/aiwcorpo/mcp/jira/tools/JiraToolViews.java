package pl.aiwcorpo.mcp.jira.tools;

import java.util.List;
import org.springframework.lang.Nullable;
import pl.aiwcorpo.mcp.jira.core.domain.IssueComment;
import pl.aiwcorpo.mcp.jira.core.domain.IssueTransition;
import pl.aiwcorpo.mcp.jira.core.domain.JiraAttachment;
import pl.aiwcorpo.mcp.jira.core.domain.JiraComment;
import pl.aiwcorpo.mcp.jira.core.domain.JiraIssue;
import pl.aiwcorpo.mcp.jira.core.domain.JiraResourceModel;
import pl.aiwcorpo.mcp.jira.core.domain.JiraUser;
import pl.aiwcorpo.mcp.jira.core.domain.JiraWorklog;
import pl.aiwcorpo.mcp.jira.core.domain.ProjectDetails;
import pl.aiwcorpo.mcp.jira.core.domain.ProjectSummary;
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
    static final int MAX_COMMENT = 5_000;
    static final int MAX_PROJECT_LIST = 50;
    static final int MAX_FILENAME = 255;
    static final int MAX_LABEL = 120;
    static final int MAX_ATTACHMENTS = 50;

    private JiraToolViews() {
    }

    record IssueView(String key, @Nullable String project, @Nullable String type, @Nullable String status,
            @Nullable String assignee, @Nullable String updated, UntrustedText summary, @Nullable UntrustedText description) {

        static IssueView of(JiraIssue issue) {
            return new IssueView(issue.key(), issue.projectKey(), JiraToolViews.name(issue.type()), JiraToolViews.name(issue.status()),
                    JiraToolViews.username(issue.assignee()), issue.updated(), summaryText(issue.summary()),
                    JiraWikiText.plain(issue.description(), MAX_DESCRIPTION));
        }
    }

    record IssueListItem(@ResourceRef(type = JiraResourceModel.ISSUE_TYPE) String key, @Nullable String type, @Nullable String status, @Nullable String assignee,
            @Nullable String updated, UntrustedText summary) {
    }

    static IssueListItem listItem(JiraIssue i) {
        return new IssueListItem(i.key(), JiraToolViews.name(i.type()), JiraToolViews.name(i.status()), JiraToolViews.username(i.assignee()), i.updated(),
                summaryText(i.summary()));
    }

    record CommentItem(String id, @Nullable String author, @Nullable String created, @Nullable String updated,
            @Nullable UntrustedText body) {

        static CommentItem of(IssueComment c) {
            return new CommentItem(c.id(), JiraToolViews.username(c.author()), c.created(), c.updated(),
                    JiraWikiText.plain(c.body(), MAX_COMMENT));
        }
    }

    record TransitionItem(String id, String name, @Nullable String toStatus) {

        static TransitionItem of(IssueTransition t) {
            return new TransitionItem(t.id(), JiraToolViews.name(t.name()), JiraToolViews.name(t.toStatus()));
        }
    }

    record TransitionsView(String issueKey, List<TransitionItem> transitions) {
    }

    record ProjectListItem(@ResourceRef(type = JiraResourceModel.PROJECT_TYPE) String key, String name, String category) {

        static ProjectListItem of(ProjectSummary p) {
            return new ProjectListItem(p.key(), JiraToolViews.label(p.name()), JiraToolViews.name(p.category()));
        }
    }

    record VersionView(String name, boolean released) {
    }

    record ProjectView(String key, String name, String category, List<String> issueTypes, List<String> components,
            List<VersionView> versions, boolean truncated) {

        static ProjectView of(ProjectDetails p) {
            boolean cut = p.issueTypes().size() > MAX_PROJECT_LIST || p.components().size() > MAX_PROJECT_LIST
                    || p.versions().size() > MAX_PROJECT_LIST;
            return new ProjectView(p.key(), JiraToolViews.label(p.name()), JiraToolViews.name(p.category()),
                    p.issueTypes().stream().limit(MAX_PROJECT_LIST).map(JiraToolViews::name).toList(),
                    p.components().stream().limit(MAX_PROJECT_LIST).map(JiraToolViews::name).toList(),
                    p.versions().stream().limit(MAX_PROJECT_LIST)
                            .map(v -> new VersionView(JiraToolViews.name(v.name()), v.released())).toList(),
                    cut);
        }
    }

    record UserItem(String username, UntrustedText displayName, boolean active) {

        static UserItem of(JiraUser u) {
            return new UserItem(JiraToolViews.username(u.name()), UntrustedText.plain(u.displayName(), MAX_USERNAME), u.active());
        }
    }

    record UsersView(String project, List<UserItem> users) {
    }

    record WorklogItem(String id, @Nullable String author, @Nullable String started, @Nullable String timeSpent,
            @Nullable UntrustedText comment) {

        static WorklogItem of(JiraWorklog w) {
            return new WorklogItem(w.id(), JiraToolViews.username(w.author()), w.started(), JiraToolViews.name(w.timeSpent()),
                    JiraWikiText.plain(w.comment(), MAX_COMMENT));
        }
    }

    record AttachmentItem(String id, UntrustedText filename, long size, @Nullable String mimeType,
            @Nullable String created, @Nullable String author) {

        static AttachmentItem of(JiraAttachment a) {
            return new AttachmentItem(a.id(), UntrustedText.plain(a.filename(), MAX_FILENAME), a.size(),
                    JiraToolViews.label(a.mimeType()), a.created(), JiraToolViews.username(a.author()));
        }
    }

    record AttachmentsView(String issueKey, List<AttachmentItem> attachments, boolean truncated) {
    }

    record CreatedView(String issueKey, String project) {
    }

    record ChangedView(String issueKey, boolean changed) {
    }

    record LinkView(String outwardIssueKey, String inwardIssueKey, String linkType) {
    }

    record WorklogCreatedView(String issueKey, String worklogId, @Nullable String timeSpent) {
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
            return new TransitionView(outcome.issueKey(), JiraToolViews.name(outcome.transition().name()),
                    JiraToolViews.name(outcome.transition().toStatus()));
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

    /** Names longer than a status name (project names, MIME types): same hygiene, {@link #MAX_LABEL} characters. */
    static String label(String text) {
        return text == null ? null : UntrustedText.cut(TextHygiene.normalize(text), MAX_LABEL);
    }

    static String name(String name) {
        return name == null ? null : UntrustedText.cut(TextHygiene.normalize(name), MAX_NAME);
    }
}
