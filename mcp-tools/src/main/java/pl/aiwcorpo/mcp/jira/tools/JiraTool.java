package pl.aiwcorpo.mcp.jira.tools;

import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Service;
import pl.aiwcorpo.mcp.jira.core.domain.CommentPage;
import pl.aiwcorpo.mcp.jira.core.domain.IssueChanges;
import pl.aiwcorpo.mcp.jira.core.domain.IssueSearch;
import pl.aiwcorpo.mcp.jira.core.domain.IssueSearchResult;
import pl.aiwcorpo.mcp.jira.core.domain.JiraAttachment;
import pl.aiwcorpo.mcp.jira.core.domain.JiraIssue;
import pl.aiwcorpo.mcp.jira.core.domain.JiraResourceModel;
import pl.aiwcorpo.mcp.jira.core.domain.JiraWorklog;
import pl.aiwcorpo.mcp.jira.core.domain.NewIssue;
import pl.aiwcorpo.mcp.jira.core.domain.ProjectSummary;
import pl.aiwcorpo.mcp.jira.application.service.JiraService;
import pl.aiwcorpo.mcp.jira.core.domain.WorklogPage;
import pl.aiwcorpo.mcp.jira.core.port.in.JiraUseCase;
import pl.aiwcorpo.mcp.jira.tools.JiraToolViews.AttachmentItem;
import pl.aiwcorpo.mcp.jira.tools.JiraToolViews.AttachmentsView;
import pl.aiwcorpo.mcp.jira.tools.JiraToolViews.ChangedView;
import pl.aiwcorpo.mcp.jira.tools.JiraToolViews.CommentItem;
import pl.aiwcorpo.mcp.jira.tools.JiraToolViews.CommentView;
import pl.aiwcorpo.mcp.jira.tools.JiraToolViews.CreatedView;
import pl.aiwcorpo.mcp.jira.tools.JiraToolViews.DeleteView;
import pl.aiwcorpo.mcp.jira.tools.JiraToolViews.IssueListItem;
import pl.aiwcorpo.mcp.jira.tools.JiraToolViews.IssueView;
import pl.aiwcorpo.mcp.jira.tools.JiraToolViews.LinkView;
import pl.aiwcorpo.mcp.jira.tools.JiraToolViews.ProjectListItem;
import pl.aiwcorpo.mcp.jira.tools.JiraToolViews.ProjectView;
import pl.aiwcorpo.mcp.jira.tools.JiraToolViews.TransitionItem;
import pl.aiwcorpo.mcp.jira.tools.JiraToolViews.TransitionView;
import pl.aiwcorpo.mcp.jira.tools.JiraToolViews.TransitionsView;
import pl.aiwcorpo.mcp.jira.tools.JiraToolViews.UserItem;
import pl.aiwcorpo.mcp.jira.tools.JiraToolViews.UsersView;
import pl.aiwcorpo.mcp.jira.tools.JiraToolViews.WorklogCreatedView;
import pl.aiwcorpo.mcp.jira.tools.JiraToolViews.WorklogItem;
import pl.aiwcorpo.mcp.platform.error.InvalidToolArgumentException;
import pl.aiwcorpo.mcp.platform.guard.RiskLevel;
import pl.aiwcorpo.mcp.platform.guard.ToolRisk;
import pl.aiwcorpo.mcp.platform.guard.ToolTitle;
import pl.aiwcorpo.mcp.platform.input.ArgumentLimits;
import pl.aiwcorpo.mcp.platform.paging.CursorCodec;
import pl.aiwcorpo.mcp.platform.paging.PageFiller;
import pl.aiwcorpo.mcp.platform.paging.PagedResult;
import pl.aiwcorpo.mcp.platform.resource.QueryScope;
import pl.aiwcorpo.mcp.platform.resource.ResourceContext;
import pl.aiwcorpo.mcp.platform.resource.ResourcePolicy;
import pl.aiwcorpo.mcp.platform.resource.ResourceRef;
import pl.aiwcorpo.mcp.platform.resource.StructuredQuery.Direction;
import pl.aiwcorpo.mcp.platform.resource.StructuredQuery.Operator;

/**
 * The MCP tools of this server. Each public {@code @Tool} method is a capability an AI client can call
 * through the gateway, so every argument is untrusted input from a model.
 *
 * <p>The gateway decides who may call a tool and asks for confirmations; the starter verifies that the call
 * came from the gateway for exactly this tool and checks the arguments against the input schema before a
 * method runs ({@link ArgumentLimits}, published by {@code SchemaLimitedToolCallbacks}): closed objects,
 * lengths and the key patterns of mcp-contracts. What stays here: a risk flag on every tool, the checks the
 * schema does not express (names, usernames, search text) and the resource always named by an explicit
 * argument, marked {@link ResourceRef}: the starter resolves it with the caller's credential and evaluates the
 * policy (C7) before a method runs, so an issue that is missing, invisible or in a restricted project never gets
 * here and the client sees the same {@code not_found_or_denied} for all three.
 */
@Service
public class JiraTool {

    /** Status, issue type, priority, link type and transition names: letters, digits, space and a little punctuation. */
    static final Pattern NAME = Pattern.compile("[\\p{L}\\p{N}][\\p{L}\\p{N} _./()&'-]{0,59}");
    /** Jira Data Center usernames. */
    static final Pattern USERNAME = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._@+-]{0,99}");
    /** What a user lookup may contain: a part of a name, login or display name. */
    static final Pattern USER_QUERY = Pattern.compile("[\\p{L}\\p{N}][\\p{L}\\p{N} ._@+-]{0,49}");
    /** Shape of an issue key (the contract pattern), for repeating a key Jira reports. */
    private static final Pattern NEW_KEY = Pattern.compile("[A-Z][A-Z0-9_]{1,9}-[1-9][0-9]{0,9}");
    static final Pattern TRANSITION_ID = Pattern.compile("[1-9][0-9]{0,9}");
    static final String COMMENT_ID_PATTERN = "^[0-9]{1,18}$";
    static final String LABEL_PATTERN = "^[A-Za-z0-9_.:-]{1,50}$";
    static final String ORDER_BY_PATTERN = "^(?:updated|created|priority|key)$";
    static final String ORDER_PATTERN = "^(?:asc|desc)$";
    /** Jira's time tracking format: weeks, days, hours, minutes, e.g. {@code 1h 30m}. */
    static final String TIME_SPENT_PATTERN = "^[0-9]{1,3}[wdhm]( [0-9]{1,3}[wdhm]){0,3}$";
    /**
     * Characters with a meaning in Jira's text search (Lucene syntax). They are replaced by spaces, which the
     * text index treats as separators anyway, so the words are searched literally.
     */
    static final Pattern TEXT_SEARCH_SYNTAX = Pattern.compile("[+\\-&|!(){}\\[\\]^~*?\\\\:\"/]");
    /** Boolean operators of the same syntax (upper case only); left in, a lone one makes Jira answer 400. */
    static final Pattern TEXT_SEARCH_OPERATOR = Pattern.compile("\\b(?:AND|OR|NOT)\\b");

    /**
     * The fields a search may filter on and sort by: a subset of the normative lists of the contracts (CC-160), handed to
     * the query builder of the starter. The project is the scope of the query ({@code narrowedTo}) and the issue type
     * is not on the list, so neither is a condition; the issue type is applied to the rows by the use case. Labels,
     * components, versions, priority and dates are not on the list either (CONTRACT_CHANGES.md, proposal).
     */
    static final Set<String> SEARCH_FIELDS = Set.of("status", "assignee", "summary", "text");
    static final Set<String> SEARCH_ORDER_FIELDS = Set.of("updated", "created", "priority", "key");

    public static final int MAX_NAME = 60;
    public static final int MAX_USERNAME = 100;
    static final int MAX_TEXT = 200;
    static final int MAX_BODY = 10_000;
    static final int MAX_SUMMARY = 255;
    static final int MAX_LABELS = 10;

    private final JiraUseCase useCase;
    private final ResourcePolicy policy;
    private final CursorCodec cursors;
    private final JiraWriteGate gate;

    public JiraTool(JiraUseCase useCase, ResourcePolicy policy, CursorCodec cursors, JiraWriteGate gate) {
        this.useCase = useCase;
        this.policy = policy;
        this.cursors = cursors;
        this.gate = gate;
    }

    /** Filters of {@code search_issues}; each is optional. */
    public record JqlFilter(
            @Nullable @ArgumentLimits(minLength = 1, maxLength = MAX_NAME) String status,
            @Nullable @ArgumentLimits(minLength = 1, maxLength = MAX_USERNAME) String assignee,
            @Nullable @ArgumentLimits(minLength = 1, maxLength = MAX_NAME) String type,
            @Nullable @ArgumentLimits(minLength = 1, maxLength = MAX_TEXT) String summary) {
    }

    // ---- reads ----

    @Tool(name = "get_issue", description = "Returns one Jira issue: key, project, type, status, assignee "
            + "username, last update time, and the summary and description as written by Jira users, each "
            + "marked as untrusted data. The description is plain text converted from Jira wiki markup "
            + "(images removed, links as text) and cut at 10000 characters.")
    @ToolRisk(RiskLevel.READ_ONLY)
    @ToolTitle("Pobierz zgłoszenie")
    public IssueView getIssue(
            @ToolParam(description = "Issue key: project key, '-', number, e.g. ABC-12")
            @ArgumentLimits(minLength = 4, maxLength = JiraResourceModel.ISSUE_KEY_MAX_LENGTH,
                    contractPattern = JiraResourceModel.ISSUE_KEY_CONTRACT)
            @ResourceRef(type = JiraResourceModel.ISSUE_TYPE) String issueKey) {
        return IssueView.of(resolved(issueKey));
    }

    @Tool(name = "search_issues", description = "Searches issues of one Jira project, optionally filtered by "
            + "status, assignee, issue type, words in the summary and words anywhere in the text. Returns a page of "
            + "about 25 issues (key, type, status, assignee username, last update time, summary as written by Jira "
            + "users, marked as untrusted data), newest update first unless order_by says otherwise. The server reads "
            + "further pages from Jira to fill a page when some issues are left out, so a page can hold up to 49. "
            + "There is no total. Pass nextCursor back with the same filters to get the next page.")
    @ToolRisk(RiskLevel.READ_ONLY)
    @ToolTitle("Szukaj zgłoszeń")
    public PagedResult<IssueListItem> searchIssues(
            @ToolParam(description = "Project key, 2-10 upper-case letters, digits or '_', starting with a letter")
            @ArgumentLimits(minLength = 2, maxLength = JiraResourceModel.PROJECT_KEY_MAX_LENGTH,
                    contractPattern = JiraResourceModel.PROJECT_KEY_CONTRACT)
            @ResourceRef(type = JiraResourceModel.PROJECT_TYPE) String project,
            @ToolParam(description = "Optional filters, all must match", required = false)
            JqlFilter jql_filter,
            @ToolParam(description = "Words searched in summary, description and comments", required = false)
            @ArgumentLimits(minLength = 1, maxLength = MAX_TEXT)
            String text,
            @ToolParam(description = "Sort field: updated (default), created, priority or key", required = false)
            @ArgumentLimits(minLength = 3, maxLength = 8, pattern = ORDER_BY_PATTERN)
            String order_by,
            @ToolParam(description = "Sort direction: desc (default) or asc", required = false)
            @ArgumentLimits(minLength = 3, maxLength = 4, pattern = ORDER_PATTERN)
            String order,
            @ToolParam(description = "nextCursor of the previous page, unchanged", required = false)
            @ArgumentLimits(maxLength = CursorCodec.MAX_CURSOR_LENGTH, pattern = CursorCodec.ARGUMENT_PATTERN)
            String cursor) {
        JqlFilter filter = jql_filter == null ? new JqlFilter(null, null, null, null) : jql_filter;
        String status = optional(filter.status(), NAME, "jql_filter.status must be a status name of 1-60 characters");
        String assignee = optional(filter.assignee(), USERNAME,
                "jql_filter.assignee must be a username of 1-100 characters");
        String type = optional(filter.type(), NAME, "jql_filter.type must be an issue type name of 1-60 characters");
        String summaryWords = filter.summary() == null ? null : searchText(filter.summary(), "jql_filter.summary");
        String words = text == null ? null : searchText(text, "text");
        String field = order_by == null ? "updated" : order_by;
        Direction direction = "asc".equals(order) ? Direction.ASC : Direction.DESC;

        String fingerprint = CursorCodec.fingerprint("search_issues", project, status, assignee, type, summaryWords,
                words, field, direction.name());
        int startAt = position(cursor, "search_issues", fingerprint);
        QueryScope scope = policy.queryScope(JiraResourceModel.PROJECT_TYPE).narrowedTo(List.of(project));
        String jql = scope.jql(SEARCH_FIELDS, SEARCH_ORDER_FIELDS)
                .where("status", Operator.EQUALS, status)
                .where("assignee", Operator.EQUALS, assignee)
                .where("summary", Operator.CONTAINS, summaryWords)
                .where("text", Operator.CONTAINS, words)
                .orderBy(field, direction)
                .build();
        PageFiller.Filled<JiraIssue> page = PageFiller.fill(cursor == null ? null : Integer.toString(startAt),
                JiraService.PAGE_SIZE, FILL_READS,
                from -> {
                    IssueSearchResult found = useCase.searchIssues(
                            new IssueSearch(project, type, jql, from == null ? 0 : Integer.parseInt(from)));
                    return new PageFiller.BackendPage<>(found.issues(),
                            found.nextStartAt() == null ? null : found.nextStartAt().toString());
                },
                rows -> policy.retainAllowedById(JiraResourceModel.ISSUE_TYPE, rows, JiraIssue::key));
        return PagedResult.of(page.items().stream().map(JiraToolViews::listItem).toList(),
                nextCursor("search_issues", fingerprint, page.next() == null ? null : Integer.valueOf(page.next())));
    }

    @Tool(name = "list_comments", description = "Lists the comments of one Jira issue, oldest first, 25 per page: "
            + "id, author username, creation and update time, and the text as written by Jira users, marked as "
            + "untrusted data (plain text converted from wiki markup, cut at 5000 characters). Pass nextCursor back "
            + "to get the next page.")
    @ToolRisk(RiskLevel.READ_ONLY)
    @ToolTitle("Lista komentarzy")
    public PagedResult<CommentItem> listComments(
            @ToolParam(description = "Issue key: project key, '-', number, e.g. ABC-12")
            @ArgumentLimits(minLength = 4, maxLength = JiraResourceModel.ISSUE_KEY_MAX_LENGTH,
                    contractPattern = JiraResourceModel.ISSUE_KEY_CONTRACT)
            @ResourceRef(type = JiraResourceModel.ISSUE_TYPE) String issueKey,
            @ToolParam(description = "nextCursor of the previous page, unchanged", required = false)
            @ArgumentLimits(maxLength = CursorCodec.MAX_CURSOR_LENGTH, pattern = CursorCodec.ARGUMENT_PATTERN)
            String cursor) {
        String key = resolved(issueKey).key();
        String fingerprint = CursorCodec.fingerprint("list_comments", key);
        CommentPage page = useCase.listComments(key, position(cursor, "list_comments", fingerprint));
        List<CommentItem> items = page.comments().stream().map(CommentItem::of).toList();
        boolean truncated = items.stream().anyMatch(c -> c.body() != null && c.body().truncated());
        return new PagedResult<>(items, nextCursor("list_comments", fingerprint, page.nextStartAt()), truncated,
                List.of());
    }

    @Tool(name = "list_transitions", description = "Lists the workflow transitions one Jira issue offers in its "
            + "current status: transition id, name and the status it leads to. Use an id or a name from this list "
            + "with transition_issue.")
    @ToolRisk(RiskLevel.READ_ONLY)
    @ToolTitle("Dostępne przejścia")
    public TransitionsView listTransitions(
            @ToolParam(description = "Issue key: project key, '-', number, e.g. ABC-12")
            @ArgumentLimits(minLength = 4, maxLength = JiraResourceModel.ISSUE_KEY_MAX_LENGTH,
                    contractPattern = JiraResourceModel.ISSUE_KEY_CONTRACT)
            @ResourceRef(type = JiraResourceModel.ISSUE_TYPE) String issueKey) {
        String key = resolved(issueKey).key();
        return new TransitionsView(key, useCase.listTransitions(key).stream().map(TransitionItem::of).toList());
    }

    @Tool(name = "get_project", description = "Returns one Jira project: key, name, category and, as set by Jira "
            + "administrators, the issue types that can be created in it, its components and its versions "
            + "(with release state), at most 50 of each. Use it to pick valid values for create_issue.")
    @ToolRisk(RiskLevel.READ_ONLY)
    @ToolTitle("Pobierz projekt")
    public ProjectView getProject(
            @ToolParam(description = "Project key, 2-10 upper-case letters, digits or '_', starting with a letter")
            @ArgumentLimits(minLength = 2, maxLength = JiraResourceModel.PROJECT_KEY_MAX_LENGTH,
                    contractPattern = JiraResourceModel.PROJECT_KEY_CONTRACT)
            @ResourceRef(type = JiraResourceModel.PROJECT_TYPE) String project) {
        return ProjectView.of(useCase.project(project));
    }

    @Tool(name = "list_projects", description = "Lists the Jira projects the caller may browse that the platform "
            + "policy lets through: key, name and category, about 25 per page (up to 49 when some projects are left out). "
            + "Pass nextCursor back to get the next page.")
    @ToolRisk(RiskLevel.READ_ONLY)
    @ToolTitle("Lista projektów")
    public PagedResult<ProjectListItem> listProjects(
            @ToolParam(description = "nextCursor of the previous page, unchanged", required = false)
            @ArgumentLimits(maxLength = CursorCodec.MAX_CURSOR_LENGTH, pattern = CursorCodec.ARGUMENT_PATTERN)
            String cursor) {
        String fingerprint = CursorCodec.fingerprint("list_projects");
        int from = position(cursor, "list_projects", fingerprint);
        List<ProjectSummary> all = useCase.listProjects();
        PageFiller.Filled<ProjectSummary> page = PageFiller.fill(Integer.toString(from), PROJECT_PAGE, FILL_READS,
                position -> {
                    int begin = Integer.parseInt(position);
                    int end = Math.min(all.size(), begin + PROJECT_PAGE);
                    return new PageFiller.BackendPage<>(begin >= all.size() ? List.<ProjectSummary>of() : all.subList(begin, end),
                            end < all.size() ? Integer.toString(end) : null);
                },
                rows -> policy.retainAllowedById(JiraResourceModel.PROJECT_TYPE, rows, ProjectSummary::key));
        return PagedResult.of(page.items().stream().map(ProjectListItem::of).toList(),
                nextCursor("list_projects", fingerprint, page.next() == null ? null : Integer.valueOf(page.next())));
    }

    @Tool(name = "find_assignable_users", description = "Finds users who can be assigned issues of one Jira project "
            + "by a part of their login or name; at most 20. Returns username (use it with assign_issue or "
            + "create_issue), display name as written by the user (untrusted data) and whether the account is "
            + "active. No e-mail addresses.")
    @ToolRisk(RiskLevel.READ_ONLY)
    @ToolTitle("Znajdź użytkowników do przypisania")
    public UsersView findAssignableUsers(
            @ToolParam(description = "Project key, 2-10 upper-case letters, digits or '_', starting with a letter")
            @ArgumentLimits(minLength = 2, maxLength = JiraResourceModel.PROJECT_KEY_MAX_LENGTH,
                    contractPattern = JiraResourceModel.PROJECT_KEY_CONTRACT)
            @ResourceRef(type = JiraResourceModel.PROJECT_TYPE) String project,
            @ToolParam(description = "Part of a login or display name, 1-50 characters")
            @ArgumentLimits(minLength = 1, maxLength = 50)
            String query) {
        if (!USER_QUERY.matcher(query).matches()) {
            throw new InvalidToolArgumentException(
                    "query must be 1-50 characters: letters, digits, space and . _ @ + -");
        }
        return new UsersView(project, useCase.assignableUsers(project, query).stream().map(UserItem::of).toList());
    }

    @Tool(name = "list_worklogs", description = "Lists the work log entries of one Jira issue, 25 per page: id, "
            + "author username, start time, time spent and the comment as written by Jira users (untrusted data). "
            + "Pass nextCursor back to get the next page.")
    @ToolRisk(RiskLevel.READ_ONLY)
    @ToolTitle("Lista wpisów czasu pracy")
    public PagedResult<WorklogItem> listWorklogs(
            @ToolParam(description = "Issue key: project key, '-', number, e.g. ABC-12")
            @ArgumentLimits(minLength = 4, maxLength = JiraResourceModel.ISSUE_KEY_MAX_LENGTH,
                    contractPattern = JiraResourceModel.ISSUE_KEY_CONTRACT)
            @ResourceRef(type = JiraResourceModel.ISSUE_TYPE) String issueKey,
            @ToolParam(description = "nextCursor of the previous page, unchanged", required = false)
            @ArgumentLimits(maxLength = CursorCodec.MAX_CURSOR_LENGTH, pattern = CursorCodec.ARGUMENT_PATTERN)
            String cursor) {
        String key = resolved(issueKey).key();
        String fingerprint = CursorCodec.fingerprint("list_worklogs", key);
        WorklogPage page = useCase.listWorklogs(key, position(cursor, "list_worklogs", fingerprint));
        List<WorklogItem> items = page.worklogs().stream().map(WorklogItem::of).toList();
        boolean truncated = items.stream().anyMatch(w -> w.comment() != null && w.comment().truncated());
        return new PagedResult<>(items, nextCursor("list_worklogs", fingerprint, page.nextStartAt()), truncated,
                List.of());
    }

    @Tool(name = "list_attachments", description = "Lists the attachments of one Jira issue (at most 50): id, file "
            + "name (untrusted data), size in bytes, MIME type, creation time and uploader. Metadata only; the "
            + "content is never fetched.")
    @ToolRisk(RiskLevel.READ_ONLY)
    @ToolTitle("Lista załączników")
    public AttachmentsView listAttachments(
            @ToolParam(description = "Issue key: project key, '-', number, e.g. ABC-12")
            @ArgumentLimits(minLength = 4, maxLength = JiraResourceModel.ISSUE_KEY_MAX_LENGTH,
                    contractPattern = JiraResourceModel.ISSUE_KEY_CONTRACT)
            @ResourceRef(type = JiraResourceModel.ISSUE_TYPE) String issueKey) {
        String key = resolved(issueKey).key();
        List<JiraAttachment> attachments = useCase.listAttachments(key);
        return new AttachmentsView(key,
                attachments.stream().limit(JiraToolViews.MAX_ATTACHMENTS).map(AttachmentItem::of).toList(),
                attachments.size() > JiraToolViews.MAX_ATTACHMENTS);
    }

    // ---- changes (UPDATE) ----

    @Tool(name = "add_comment", description = "Adds a comment to one Jira issue as the calling user. The text is "
            + "stored as plain text in a preformatted block; Jira markup in it is not interpreted. Returns the "
            + "key Jira knows the issue under (differs from the given one when the issue was moved), the id of "
            + "the new comment and its creation time. Calling it twice adds two comments.")
    @ToolRisk(RiskLevel.UPDATE)
    @ToolTitle("Dodaj komentarz")
    public CommentView addComment(
            @ToolParam(description = "Issue key: project key, '-', number, e.g. ABC-12")
            @ArgumentLimits(minLength = 4, maxLength = JiraResourceModel.ISSUE_KEY_MAX_LENGTH,
                    contractPattern = JiraResourceModel.ISSUE_KEY_CONTRACT)
            @ResourceRef(type = JiraResourceModel.ISSUE_TYPE) String issueKey,
            @ToolParam(description = "Comment text, plain text")
            @ArgumentLimits(minLength = 1, maxLength = MAX_BODY)
            String body) {
        gate.requireWritable();
        requireText(body, "body");
        return CommentView.of(useCase.addComment(resolved(issueKey).key(), body));
    }

    @Tool(name = "edit_comment", description = "Replaces the text of one comment of a Jira issue, as the calling "
            + "user (Jira decides whose comments they may edit). The text is stored as plain text in a "
            + "preformatted block. Repeating the call leaves the same text. Returns the issue key, the comment id "
            + "and the comment creation time.")
    @ToolRisk(RiskLevel.UPDATE)
    @ToolTitle("Edytuj komentarz")
    public CommentView editComment(
            @ToolParam(description = "Issue key: project key, '-', number, e.g. ABC-12")
            @ArgumentLimits(minLength = 4, maxLength = JiraResourceModel.ISSUE_KEY_MAX_LENGTH,
                    contractPattern = JiraResourceModel.ISSUE_KEY_CONTRACT)
            @ResourceRef(type = JiraResourceModel.ISSUE_TYPE) String issueKey,
            @ToolParam(description = "Comment id from list_comments or add_comment")
            @ArgumentLimits(minLength = 1, maxLength = 18, pattern = COMMENT_ID_PATTERN)
            String commentId,
            @ToolParam(description = "New comment text, plain text")
            @ArgumentLimits(minLength = 1, maxLength = MAX_BODY)
            String body) {
        gate.requireWritable();
        requireText(body, "body");
        return CommentView.of(useCase.editComment(resolved(issueKey).key(), commentId, body));
    }

    @Tool(name = "transition_issue", description = "Moves one Jira issue through a workflow transition "
            + "available in its current status, given by transition id or name. A name shared by several "
            + "transitions is refused with the ids to choose from. Returns the key Jira knows the issue under, "
            + "the transition name and the status the issue moved to. Repeating the call after it succeeded "
            + "fails (the transition is no longer offered) instead of moving the issue again.")
    @ToolRisk(RiskLevel.UPDATE)
    @ToolTitle("Zmień status zgłoszenia")
    public TransitionView transitionIssue(
            @ToolParam(description = "Issue key: project key, '-', number, e.g. ABC-12")
            @ArgumentLimits(minLength = 4, maxLength = JiraResourceModel.ISSUE_KEY_MAX_LENGTH,
                    contractPattern = JiraResourceModel.ISSUE_KEY_CONTRACT)
            @ResourceRef(type = JiraResourceModel.ISSUE_TYPE) String issueKey,
            @ToolParam(description = "Transition id, or transition name available in the issue's current status")
            @ArgumentLimits(minLength = 1, maxLength = MAX_NAME)
            String transition) {
        gate.requireWritable();
        if (!(TRANSITION_ID.matcher(transition).matches() || NAME.matcher(transition).matches())) {
            throw new InvalidToolArgumentException("transition must be a transition id or a name of 1-60 characters");
        }
        return TransitionView.of(useCase.transitionIssue(resolved(issueKey).key(), transition));
    }

    @Tool(name = "create_issue", description = "Creates a Jira issue in one project as the calling user. Needs the "
            + "name of an issue type of that project (see get_project) and a summary; description, labels, "
            + "priority and assignee username are optional. The description is stored as plain text in a "
            + "preformatted block. Projects that require other fields refuse the call. Returns the key of the new "
            + "issue. Calling it twice creates two issues.")
    @ToolRisk(RiskLevel.UPDATE)
    @ToolTitle("Utwórz zgłoszenie")
    public CreatedView createIssue(
            @ToolParam(description = "Project key, 2-10 upper-case letters, digits or '_', starting with a letter")
            @ArgumentLimits(minLength = 2, maxLength = JiraResourceModel.PROJECT_KEY_MAX_LENGTH,
                    contractPattern = JiraResourceModel.PROJECT_KEY_CONTRACT)
            @ResourceRef(type = JiraResourceModel.PROJECT_TYPE) String project,
            @ToolParam(description = "Issue type name, e.g. Task or Bug")
            @ArgumentLimits(minLength = 1, maxLength = MAX_NAME)
            String issueType,
            @ToolParam(description = "One-line summary, 1-255 characters")
            @ArgumentLimits(minLength = 1, maxLength = MAX_SUMMARY)
            String summary,
            @ToolParam(description = "Description, plain text", required = false)
            @ArgumentLimits(minLength = 1, maxLength = MAX_BODY)
            String description,
            @ToolParam(description = "Labels, up to 10, letters, digits and . _ : -", required = false)
            @ArgumentLimits(maxItems = MAX_LABELS, minLength = 1, maxLength = 50, pattern = LABEL_PATTERN)
            List<String> labels,
            @ToolParam(description = "Priority name, e.g. High", required = false)
            @ArgumentLimits(minLength = 1, maxLength = MAX_NAME)
            String priority,
            @ToolParam(description = "Username of the assignee", required = false)
            @ArgumentLimits(minLength = 1, maxLength = MAX_USERNAME)
            String assignee) {
        gate.requireWritable();
        optional(issueType, NAME, "issueType must be an issue type name of 1-60 characters");
        requireSummary(summary);
        if (description != null) {
            requireText(description, "description");
        }
        optional(priority, NAME, "priority must be a priority name of 1-60 characters");
        optional(assignee, USERNAME, "assignee must be a username of 1-100 characters");
        String key = useCase.createIssue(new NewIssue(project, issueType, summary, description, labels, priority, assignee));
        return new CreatedView(key, project);
    }

    @Tool(name = "update_issue", description = "Changes fields of one Jira issue as the calling user: summary, "
            + "description (stored as plain text in a preformatted block), labels (replaces the whole set; an "
            + "empty list removes all) and priority. At least one field is required. Repeating the call leaves "
            + "the same state. Returns the key Jira knows the issue under.")
    @ToolRisk(RiskLevel.UPDATE)
    @ToolTitle("Edytuj zgłoszenie")
    public ChangedView updateIssue(
            @ToolParam(description = "Issue key: project key, '-', number, e.g. ABC-12")
            @ArgumentLimits(minLength = 4, maxLength = JiraResourceModel.ISSUE_KEY_MAX_LENGTH,
                    contractPattern = JiraResourceModel.ISSUE_KEY_CONTRACT)
            @ResourceRef(type = JiraResourceModel.ISSUE_TYPE) String issueKey,
            @ToolParam(description = "New one-line summary, 1-255 characters", required = false)
            @ArgumentLimits(minLength = 1, maxLength = MAX_SUMMARY)
            String summary,
            @ToolParam(description = "New description, plain text", required = false)
            @ArgumentLimits(minLength = 1, maxLength = MAX_BODY)
            String description,
            @ToolParam(description = "New set of labels, up to 10", required = false)
            @ArgumentLimits(maxItems = MAX_LABELS, minLength = 1, maxLength = 50, pattern = LABEL_PATTERN)
            List<String> labels,
            @ToolParam(description = "Priority name, e.g. High", required = false)
            @ArgumentLimits(minLength = 1, maxLength = MAX_NAME)
            String priority) {
        gate.requireWritable();
        IssueChanges changes = new IssueChanges(summary, description, labels, priority);
        if (changes.isEmpty()) {
            throw new InvalidToolArgumentException("update_issue needs at least one of summary, description, labels, priority");
        }
        if (summary != null) {
            requireSummary(summary);
        }
        if (description != null) {
            requireText(description, "description");
        }
        optional(priority, NAME, "priority must be a priority name of 1-60 characters");
        String key = resolved(issueKey).key();
        useCase.updateIssue(key, changes);
        return new ChangedView(key, true);
    }

    @Tool(name = "assign_issue", description = "Assigns one Jira issue to a user, given by Jira username (see "
            + "find_assignable_users). Repeating the call leaves the same state. Returns the key Jira knows the "
            + "issue under.")
    @ToolRisk(RiskLevel.UPDATE)
    @ToolTitle("Przypisz zgłoszenie")
    public ChangedView assignIssue(
            @ToolParam(description = "Issue key: project key, '-', number, e.g. ABC-12")
            @ArgumentLimits(minLength = 4, maxLength = JiraResourceModel.ISSUE_KEY_MAX_LENGTH,
                    contractPattern = JiraResourceModel.ISSUE_KEY_CONTRACT)
            @ResourceRef(type = JiraResourceModel.ISSUE_TYPE) String issueKey,
            @ToolParam(description = "Username of the new assignee")
            @ArgumentLimits(minLength = 1, maxLength = MAX_USERNAME)
            String assignee) {
        gate.requireWritable();
        optional(assignee, USERNAME, "assignee must be a username of 1-100 characters");
        String key = resolved(issueKey).key();
        useCase.assign(key, assignee);
        return new ChangedView(key, true);
    }

    @Tool(name = "unassign_issue", description = "Removes the assignee of one Jira issue. Repeating the call leaves "
            + "the same state. Returns the key Jira knows the issue under.")
    @ToolRisk(RiskLevel.UPDATE)
    @ToolTitle("Usuń przypisanie zgłoszenia")
    public ChangedView unassignIssue(
            @ToolParam(description = "Issue key: project key, '-', number, e.g. ABC-12")
            @ArgumentLimits(minLength = 4, maxLength = JiraResourceModel.ISSUE_KEY_MAX_LENGTH,
                    contractPattern = JiraResourceModel.ISSUE_KEY_CONTRACT)
            @ResourceRef(type = JiraResourceModel.ISSUE_TYPE) String issueKey) {
        gate.requireWritable();
        String key = resolved(issueKey).key();
        useCase.assign(key, null);
        return new ChangedView(key, true);
    }

    @Tool(name = "link_issues", description = "Links two Jira issues with a link type given by name, e.g. Blocks or "
            + "Relates. In Jira's terms the outward issue acts on the inward one: with type Blocks, the outward "
            + "issue blocks the inward issue. The caller needs access to both issues. Calling it twice may leave "
            + "two links.")
    @ToolRisk(RiskLevel.UPDATE)
    @ToolTitle("Powiąż zgłoszenia")
    public LinkView linkIssues(
            @ToolParam(description = "Key of the outward issue, e.g. ABC-12")
            @ArgumentLimits(minLength = 4, maxLength = JiraResourceModel.ISSUE_KEY_MAX_LENGTH,
                    contractPattern = JiraResourceModel.ISSUE_KEY_CONTRACT)
            @ResourceRef(type = JiraResourceModel.ISSUE_TYPE) String outwardIssueKey,
            @ToolParam(description = "Key of the inward issue, e.g. ABC-13")
            @ArgumentLimits(minLength = 4, maxLength = JiraResourceModel.ISSUE_KEY_MAX_LENGTH,
                    contractPattern = JiraResourceModel.ISSUE_KEY_CONTRACT)
            @ResourceRef(type = JiraResourceModel.ISSUE_TYPE) String inwardIssueKey,
            @ToolParam(description = "Link type name, e.g. Blocks, Relates, Duplicate")
            @ArgumentLimits(minLength = 1, maxLength = MAX_NAME)
            String linkType) {
        gate.requireWritable();
        optional(linkType, NAME, "linkType must be a link type name of 1-60 characters");
        String outward = resolved(outwardIssueKey).key();
        String inward = resolved(inwardIssueKey).key();
        if (outward.equals(inward)) {
            throw new InvalidToolArgumentException("An issue cannot be linked to itself.");
        }
        useCase.linkIssues(linkType, inward, outward);
        return new LinkView(outward, inward, JiraToolViews.name(linkType));
    }

    @Tool(name = "add_worklog", description = "Logs work on one Jira issue as the calling user, in Jira's time "
            + "format (e.g. '1h 30m', '2d'), with an optional comment stored as plain text in a preformatted "
            + "block. Returns the key Jira knows the issue under and the id of the entry. Calling it twice logs "
            + "the time twice.")
    @ToolRisk(RiskLevel.UPDATE)
    @ToolTitle("Zaloguj czas pracy")
    public WorklogCreatedView addWorklog(
            @ToolParam(description = "Issue key: project key, '-', number, e.g. ABC-12")
            @ArgumentLimits(minLength = 4, maxLength = JiraResourceModel.ISSUE_KEY_MAX_LENGTH,
                    contractPattern = JiraResourceModel.ISSUE_KEY_CONTRACT)
            @ResourceRef(type = JiraResourceModel.ISSUE_TYPE) String issueKey,
            @ToolParam(description = "Time spent: numbers with w, d, h or m, e.g. '1h 30m'")
            @ArgumentLimits(minLength = 2, maxLength = 20, pattern = TIME_SPENT_PATTERN)
            String timeSpent,
            @ToolParam(description = "Comment, plain text", required = false)
            @ArgumentLimits(minLength = 1, maxLength = MAX_BODY)
            String comment) {
        gate.requireWritable();
        if (comment != null) {
            requireText(comment, "comment");
        }
        String key = resolved(issueKey).key();
        JiraWorklog worklog = useCase.addWorklog(key, timeSpent, comment);
        return new WorklogCreatedView(key, worklog.id(), JiraToolViews.name(worklog.timeSpent()));
    }

    /**
     * Deletion cannot be undone, so the risk is DESTRUCTIVE: the gateway asks for the one-time code and the policy
     * checks the {@code delete} action on the issue. Nothing here asks "are you sure"; the confirmation is not the
     * server's. Jira refuses an issue that has subtasks, and the tool does not delete them.
     */
    @Tool(name = "delete_issue", description = "Permanently deletes one Jira issue. An issue with subtasks is "
            + "refused. Returns the key Jira knew the issue under (differs from the given one when the issue "
            + "was moved).")
    @ToolRisk(RiskLevel.DESTRUCTIVE)
    @ToolTitle("Usuń zgłoszenie")
    public DeleteView deleteIssue(
            @ToolParam(description = "Issue key: project key, '-', number, e.g. ABC-12")
            @ArgumentLimits(minLength = 4, maxLength = JiraResourceModel.ISSUE_KEY_MAX_LENGTH,
                    contractPattern = JiraResourceModel.ISSUE_KEY_CONTRACT)
            @ResourceRef(type = JiraResourceModel.ISSUE_TYPE) String issueKey) {
        gate.requireWritable();
        String key = resolved(issueKey).key();
        if (!key.equals(issueKey)) {
            // The one-time code the human entered is bound to the key in the arguments; deleting another issue
            // (a moved one under its new key) would not be what was confirmed. The policy has already allowed the
            // new key, so naming it discloses nothing the caller could not read.
            throw new InvalidToolArgumentException(NEW_KEY.matcher(key).matches()
                    ? "The issue now has the key " + key + "; call again with that key."
                    : "The issue now has another key; call again with its current key.");
        }
        useCase.deleteIssue(key);
        return new DeleteView(key, true);
    }

    /** Rows of one page of {@code list_projects}. */
    static final int PROJECT_PAGE = JiraService.PAGE_SIZE;
    /**
     * Backend pages one call may read to fill a page that the policy thinned out: a short page with a cursor would tell
     * the caller that something was hidden (PageFiller). Each read is a call to Jira, so the number is small.
     */
    static final int FILL_READS = 4;

    /**
     * The issue the resolver fetched for the policy: the one judged is the one returned, no second call to Jira, and
     * the key is the one Jira reports (the write goes to it, not to the requested key of a moved issue).
     */
    private static JiraIssue resolved(String issueKey) {
        return ResourceContext.current().orElseThrow()
                .fetched(JiraResourceModel.ISSUE_TYPE, issueKey, JiraIssue.class).orElseThrow();
    }

    /** The offset a cursor carries, 0 without one. A cursor of another user, tool or query is refused by the codec. */
    private int position(String cursor, String tool, String fingerprint) {
        if (cursor == null) {
            return 0;
        }
        try {
            return Integer.parseInt(cursors.open(cursor, tool, fingerprint));
        } catch (NumberFormatException notOurs) {
            throw new InvalidToolArgumentException(CursorCodec.REFUSED_MESSAGE);
        }
    }

    private String nextCursor(String tool, String fingerprint, Integer nextStartAt) {
        return nextStartAt == null ? null : cursors.issue(tool, fingerprint, Integer.toString(nextStartAt));
    }

    private static String optional(String value, Pattern pattern, String message) {
        if (value != null && !pattern.matcher(value).matches()) {
            throw new InvalidToolArgumentException(message);
        }
        return value;
    }

    private static void requireText(String text, String argument) {
        if (text.isBlank() || hasControlCharacters(text, true)) {
            throw new InvalidToolArgumentException(argument + " must be 1-10000 characters of text; line breaks and "
                    + "tabs are the only control characters allowed");
        }
    }

    private static void requireSummary(String summary) {
        if (summary.isBlank() || hasControlCharacters(summary, false)) {
            throw new InvalidToolArgumentException("summary must be 1-255 characters on one line without control characters");
        }
    }

    /** Words for Jira's text search with its query syntax removed; at least one letter or digit must remain. */
    private static String searchText(String text, String argument) {
        String message = argument + " must be 1-200 characters without control characters and contain a letter or digit";
        if (hasControlCharacters(text, false)) {
            throw new InvalidToolArgumentException(message);
        }
        String words = TEXT_SEARCH_OPERATOR.matcher(TEXT_SEARCH_SYNTAX.matcher(text).replaceAll(" ")).replaceAll(" ")
                .trim().replaceAll("\\s+", " ");
        if (words.codePoints().noneMatch(Character::isLetterOrDigit)) {
            throw new InvalidToolArgumentException(message);
        }
        return words;
    }

    private static boolean hasControlCharacters(String value, boolean allowLineBreaks) {
        return value.chars().anyMatch(c -> Character.isISOControl(c)
                && !(allowLineBreaks && (c == '\n' || c == '\r' || c == '\t')));
    }
}
