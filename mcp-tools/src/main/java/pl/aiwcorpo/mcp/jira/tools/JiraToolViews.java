package pl.aiwcorpo.mcp.jira.tools;

import java.util.List;
import org.springframework.lang.Nullable;
import pl.aiwcorpo.mcp.jira.core.domain.AttachmentText;
import pl.aiwcorpo.mcp.jira.core.domain.ChangelogEntry;
import pl.aiwcorpo.mcp.jira.core.domain.CreateFields;
import pl.aiwcorpo.mcp.jira.core.domain.CurrentUser;
import pl.aiwcorpo.mcp.jira.core.domain.IssueComment;
import pl.aiwcorpo.mcp.jira.core.domain.IssueLink;
import pl.aiwcorpo.mcp.jira.core.domain.IssueTransition;
import pl.aiwcorpo.mcp.jira.core.domain.IssueTypeInfo;
import pl.aiwcorpo.mcp.jira.core.domain.IssueTypeStatuses;
import pl.aiwcorpo.mcp.jira.core.domain.JiraAttachment;
import pl.aiwcorpo.mcp.jira.core.domain.JiraBoard;
import pl.aiwcorpo.mcp.jira.core.domain.JiraComment;
import pl.aiwcorpo.mcp.jira.core.domain.JiraIssue;
import pl.aiwcorpo.mcp.jira.core.domain.JiraResourceModel;
import pl.aiwcorpo.mcp.jira.core.domain.JiraSprint;
import pl.aiwcorpo.mcp.jira.core.domain.JiraUser;
import pl.aiwcorpo.mcp.jira.core.domain.JiraWorklog;
import pl.aiwcorpo.mcp.jira.core.domain.LinkType;
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
    static final int MAX_CHANGE_TEXT = 500;
    static final int MAX_CHANGES_PER_ENTRY = 50;
    static final int MAX_ALLOWED_VALUES = 20;
    static final int MAX_LINK_TYPES = 50;
    static final int MAX_LINKS = 50;
    static final int MAX_GOAL = 500;

    private JiraToolViews() {
    }

    record LinkItem(String id, String type, String direction, @Nullable String description, String issueKey) {

        static LinkItem of(IssueLink link) {
            return new LinkItem(link.id(), JiraToolViews.name(link.typeName()), link.direction(),
                    label(link.description()), link.otherKey());
        }
    }

    record IssueView(String key, @Nullable String project, @Nullable String type, @Nullable String status,
            @Nullable String assignee, @Nullable String updated, UntrustedText summary, @Nullable UntrustedText description,
            List<LinkItem> links, boolean linksTruncated) {

        /** @param readable the links to issues the caller may read; the others are not counted or shown */
        static IssueView of(JiraIssue issue, List<IssueLink> readable) {
            return new IssueView(issue.key(), issue.projectKey(), JiraToolViews.name(issue.type()), JiraToolViews.name(issue.status()),
                    JiraToolViews.username(issue.assignee()), timestamp(issue.updated()), summaryText(issue.summary()),
                    JiraWikiText.plain(issue.description(), MAX_DESCRIPTION),
                    readable.stream().limit(MAX_LINKS).map(LinkItem::of).toList(), readable.size() > MAX_LINKS);
        }
    }

    record IssueListItem(@ResourceRef(type = JiraResourceModel.ISSUE_TYPE) String key, @Nullable String type, @Nullable String status, @Nullable String assignee,
            @Nullable String updated, UntrustedText summary) {
    }

    static IssueListItem listItem(JiraIssue i) {
        return new IssueListItem(i.key(), JiraToolViews.name(i.type()), JiraToolViews.name(i.status()), JiraToolViews.username(i.assignee()), timestamp(i.updated()),
                summaryText(i.summary()));
    }

    record CommentItem(String id, @Nullable String author, @Nullable String created, @Nullable String updated,
            @Nullable UntrustedText body) {

        static CommentItem of(IssueComment c) {
            return new CommentItem(c.id(), JiraToolViews.username(c.author()), timestamp(c.created()), timestamp(c.updated()),
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
            return new WorklogItem(w.id(), JiraToolViews.username(w.author()), timestamp(w.started()), JiraToolViews.name(w.timeSpent()),
                    JiraWikiText.plain(w.comment(), MAX_COMMENT));
        }
    }

    record AttachmentItem(String id, UntrustedText filename, long size, @Nullable String mimeType,
            @Nullable String created, @Nullable String author) {

        static AttachmentItem of(JiraAttachment a) {
            return new AttachmentItem(a.id(), UntrustedText.plain(a.filename(), MAX_FILENAME), a.size(),
                    JiraToolViews.label(a.mimeType()), timestamp(a.created()), JiraToolViews.username(a.author()));
        }
    }

    record AttachmentsView(String issueKey, List<AttachmentItem> attachments, boolean truncated) {
    }

    record FieldChangeItem(@Nullable String field, @Nullable UntrustedText from, @Nullable UntrustedText to) {
    }

    record ChangelogItem(String id, @Nullable UntrustedText author, @Nullable String created,
            List<FieldChangeItem> changes, boolean redacted) {

        /** @param readableKeys keys of the issues named in the page that the caller may read */
        static ChangelogItem of(ChangelogEntry entry, java.util.Set<String> readableKeys) {
            boolean[] redacted = {false};
            List<FieldChangeItem> changes = entry.changes().stream().limit(MAX_CHANGES_PER_ENTRY).map(c -> {
                if (refersToOtherResources(c.field()) && !readable(c, readableKeys)) {
                    redacted[0] = true;
                    return new FieldChangeItem(name(c.field()), null, null);
                }
                return new FieldChangeItem(name(c.field()),
                        c.from() == null ? null : UntrustedText.plain(c.from(), MAX_CHANGE_TEXT),
                        c.to() == null ? null : UntrustedText.plain(c.to(), MAX_CHANGE_TEXT));
            }).toList();
            return new ChangelogItem(entry.id(),
                    entry.author() == null ? null : UntrustedText.plain(entry.author(), MAX_USERNAME),
                    timestamp(entry.created()), changes, redacted[0]);
        }

        /** True when a text was cut or the entry has more field changes than are shown. */
        static boolean truncated(ChangelogEntry entry, ChangelogItem item) {
            return entry.changes().size() > MAX_CHANGES_PER_ENTRY
                    || item.changes().stream().anyMatch(c -> c.from() != null && c.from().truncated()
                            || c.to() != null && c.to().truncated());
        }

        private static boolean readable(ChangelogEntry.FieldChange change, java.util.Set<String> readableKeys) {
            // The project field holds project names and ids, which cannot be judged by key: never shown.
            return !"project".equalsIgnoreCase(change.field()) && readableKeys.containsAll(referencedKeys(change));
        }
    }

    private static final java.util.regex.Pattern ISSUE_KEY_IN_TEXT = java.util.regex.Pattern
            .compile("(?<![A-Za-z0-9_])[A-Z][A-Z0-9_]{1,9}-[1-9][0-9]{0,9}(?![A-Za-z0-9_])");

    /**
     * Fields whose old and new value point at other issues or projects (Link, Parent, Parent Link, Epic Link, Epic Child,
     * Key, project, Sub-task, issuelinks and the like). A name that merely contains one of the words counts, so an
     * unknown variant is treated as a reference.
     */
    static boolean refersToOtherResources(String field) {
        if (field == null) {
            return false;
        }
        String lower = field.toLowerCase(java.util.Locale.ROOT);
        return lower.equals("key") || lower.equals("project") || lower.contains("link") || lower.contains("parent")
                || lower.contains("epic") || lower.contains("sub-task") || lower.contains("subtask");
    }

    /** Issue keys in the old and the new value of a change. */
    static java.util.Set<String> referencedKeys(ChangelogEntry.FieldChange change) {
        java.util.Set<String> keys = new java.util.LinkedHashSet<>();
        for (String text : new String[] {change.from(), change.to()}) {
            if (text != null) {
                ISSUE_KEY_IN_TEXT.matcher(text).results().forEach(m -> keys.add(m.group()));
            }
        }
        return keys;
    }

    record StatusItem(String name, @Nullable String category) {
    }

    record IssueTypeStatusesItem(String issueType, List<StatusItem> statuses) {
    }

    record StatusesView(String project, List<IssueTypeStatusesItem> issueTypes, boolean truncated) {

        static StatusesView of(String project, List<IssueTypeStatuses> types) {
            boolean cut = types.size() > MAX_PROJECT_LIST
                    || types.stream().anyMatch(t -> t.statuses().size() > MAX_PROJECT_LIST);
            return new StatusesView(project, types.stream().limit(MAX_PROJECT_LIST)
                    .map(t -> new IssueTypeStatusesItem(JiraToolViews.name(t.issueType()), t.statuses().stream().limit(MAX_PROJECT_LIST)
                            .map(st -> new StatusItem(JiraToolViews.name(st.name()), JiraToolViews.name(st.category()))).toList()))
                    .toList(), cut);
        }
    }

    record IssueTypeItem(String id, String name, boolean subtask) {

        static IssueTypeItem of(IssueTypeInfo type) {
            return new IssueTypeItem(type.id(), JiraToolViews.name(type.name()), type.subtask());
        }
    }

    record AllowedValueItem(@Nullable String id, String name) {
    }

    record CreateFieldItem(String fieldId, String name, boolean required, @Nullable String type,
            @Nullable String itemsType, List<AllowedValueItem> allowedValues, boolean allowedValuesTruncated) {

        static CreateFieldItem of(CreateFields.Field f) {
            return new CreateFieldItem(f.fieldId(), label(f.name()), f.required(), JiraToolViews.name(f.type()), JiraToolViews.name(f.itemsType()),
                    f.allowedValues().stream().limit(MAX_ALLOWED_VALUES)
                            .map(v -> new AllowedValueItem(JiraToolViews.name(v.id()), label(v.name()))).toList(),
                    f.allowedValues().size() > MAX_ALLOWED_VALUES);
        }
    }

    record CreateFieldsView(String project, String issueTypeId, List<CreateFieldItem> fields, boolean truncated) {

        static CreateFieldsView of(String project, String issueTypeId, CreateFields found) {
            List<CreateFieldItem> fields = found.fields().stream().map(CreateFieldItem::of).toList();
            return new CreateFieldsView(project, issueTypeId, fields,
                    found.truncated() || fields.stream().anyMatch(CreateFieldItem::allowedValuesTruncated));
        }
    }

    record LinkTypeItem(@Nullable String id, String name, @Nullable String inward, @Nullable String outward) {

        static LinkTypeItem of(LinkType t) {
            return new LinkTypeItem(JiraToolViews.name(t.id()), JiraToolViews.name(t.name()), label(t.inward()), label(t.outward()));
        }
    }

    record LinkTypesView(List<LinkTypeItem> linkTypes, boolean truncated) {
    }

    record CurrentUserView(String username, @Nullable String key, UntrustedText displayName, boolean active) {

        static CurrentUserView of(CurrentUser u) {
            return new CurrentUserView(JiraToolViews.username(u.name()), JiraToolViews.username(u.key()),
                    UntrustedText.plain(u.displayName(), MAX_USERNAME), u.active());
        }
    }

    /** {@code omitted} is empty when {@code content} is the file's text, else one fixed code saying why it is not. */
    record AttachmentContentView(String issueKey, String id, UntrustedText filename, @Nullable String mimeType,
            UntrustedText content, List<String> omitted) {

        static AttachmentContentView of(String issueKey, AttachmentText file) {
            JiraAttachment attachment = file.attachment();
            return new AttachmentContentView(issueKey, attachment.id(), UntrustedText.plain(attachment.filename(), MAX_FILENAME),
                    label(attachment.mimeType()), UntrustedText.plainCut(file.text(), file.truncated()),
                    file.omittedReason() == null ? List.of() : List.of(file.omittedReason()));
        }
    }

    record BoardItem(String id, UntrustedText name, @Nullable String type, @Nullable String project) {

        static BoardItem of(JiraBoard b) {
            return new BoardItem(b.id(), UntrustedText.plain(b.name(), MAX_LABEL), JiraToolViews.name(b.type()), b.projectKey());
        }
    }

    record SprintItem(String id, UntrustedText name, @Nullable String state, @Nullable String startDate,
            @Nullable String endDate, @Nullable String completeDate, @Nullable UntrustedText goal) {

        static SprintItem of(JiraSprint s) {
            return new SprintItem(s.id(), UntrustedText.plain(s.name(), MAX_LABEL), JiraToolViews.name(s.state()), timestamp(s.startDate()),
                    timestamp(s.endDate()), timestamp(s.completeDate()),
                    s.goal() == null || s.goal().isBlank() ? null : UntrustedText.plain(s.goal(), MAX_GOAL));
        }
    }

    record WatcherView(String issueKey, String username) {
    }

    record UnlinkView(String outwardIssueKey, String inwardIssueKey, String linkId) {
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
            return new CommentView(comment.issueKey(), comment.id(), timestamp(comment.created()));
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

    private static final java.util.regex.Pattern TIMESTAMP = java.util.regex.Pattern
            .compile("[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9:.]{5,16}(?:Z|[+-][0-9]{2}:?[0-9]{2})?");

    /** Times are ISO 8601 as Jira writes them; anything else in such a field is not passed on to a model. */
    static String timestamp(String value) {
        return value != null && TIMESTAMP.matcher(value).matches() ? value : null;
    }

    /** Names longer than a status name (project names, MIME types): same hygiene, {@link #MAX_LABEL} characters. */
    static String label(String text) {
        return text == null ? null : UntrustedText.cut(TextHygiene.normalize(text), MAX_LABEL);
    }

    static String name(String name) {
        return name == null ? null : UntrustedText.cut(TextHygiene.normalize(name), MAX_NAME);
    }
}
