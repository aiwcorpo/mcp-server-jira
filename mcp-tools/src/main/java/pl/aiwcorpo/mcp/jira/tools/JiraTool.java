package pl.aiwcorpo.mcp.jira.tools;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Service;
import pl.aiwcorpo.mcp.jira.core.domain.IssueSearch;
import pl.aiwcorpo.mcp.jira.core.domain.IssueSearchResult;
import pl.aiwcorpo.mcp.jira.core.domain.JiraIssue;
import pl.aiwcorpo.mcp.jira.core.domain.JiraResourceModel;
import pl.aiwcorpo.mcp.jira.core.port.in.JiraUseCase;
import pl.aiwcorpo.mcp.jira.tools.JiraToolViews.CommentView;
import pl.aiwcorpo.mcp.jira.tools.JiraToolViews.DeleteView;
import pl.aiwcorpo.mcp.jira.tools.JiraToolViews.IssueView;
import pl.aiwcorpo.mcp.jira.tools.JiraToolViews.SearchView;
import pl.aiwcorpo.mcp.jira.tools.JiraToolViews.TransitionView;
import pl.aiwcorpo.mcp.platform.error.InvalidToolArgumentException;
import pl.aiwcorpo.mcp.platform.guard.RiskLevel;
import pl.aiwcorpo.mcp.platform.guard.ToolRisk;
import pl.aiwcorpo.mcp.platform.guard.ToolTitle;
import pl.aiwcorpo.mcp.platform.input.ArgumentLimits;
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

    /** Status, issue type and transition names: letters, digits, space and a little punctuation. */
    static final Pattern NAME = Pattern.compile("[\\p{L}\\p{N}][\\p{L}\\p{N} _./()&'-]{0,59}");
    /** Jira Data Center usernames. */
    static final Pattern USERNAME = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._@+-]{0,99}");
    /** Shape of an issue key (the contract pattern), for repeating a key Jira reports. */
    private static final Pattern NEW_KEY = Pattern.compile("[A-Z][A-Z0-9_]{1,9}-[1-9][0-9]{0,9}");
    static final Pattern TRANSITION_ID = Pattern.compile("[1-9][0-9]{0,9}");
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
     * is not on the list, so neither is a condition; the issue type is applied to the rows by the use case.
     */
    static final Set<String> SEARCH_FIELDS = Set.of("status", "assignee", "text");
    static final Set<String> SEARCH_ORDER_FIELDS = Set.of("updated");

    public static final int MAX_NAME = 60;
    /** One page only; the model narrows the search instead of paging through a project. */
    static final int MAX_SEARCH_RESULTS = 25;
    public static final int MAX_USERNAME = 100;
    static final int MAX_TEXT = 200;
    static final int MAX_BODY = 10_000;

    private final JiraUseCase useCase;
    private final ResourcePolicy policy;

    public JiraTool(JiraUseCase useCase, ResourcePolicy policy) {
        this.useCase = useCase;
        this.policy = policy;
    }

    /** Filters of {@code search_issues}; each is optional. */
    public record JqlFilter(
            @Nullable @ArgumentLimits(minLength = 1, maxLength = MAX_NAME) String status,
            @Nullable @ArgumentLimits(minLength = 1, maxLength = MAX_USERNAME) String assignee,
            @Nullable @ArgumentLimits(minLength = 1, maxLength = MAX_NAME) String type) {
    }

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
            + "status, assignee, issue type and words in the text. Returns at most 25 issues, most recently "
            + "updated first: key, type, status, assignee username, last update time and the summary as written "
            + "by Jira users, marked as untrusted data. Issues the caller may not see are left out, so the list "
            + "carries no total; hasMore is true when matching issues may have been left out (more than 25 "
            + "matches, or an issue type filter that could only be applied to the 100 most recently updated issues).")
    @ToolRisk(RiskLevel.READ_ONLY)
    @ToolTitle("Szukaj zgłoszeń")
    public SearchView searchIssues(
            @ToolParam(description = "Project key, 2-10 upper-case letters, digits or '_', starting with a letter")
            @ArgumentLimits(minLength = 2, maxLength = JiraResourceModel.PROJECT_KEY_MAX_LENGTH,
                    contractPattern = JiraResourceModel.PROJECT_KEY_CONTRACT)
            @ResourceRef(type = JiraResourceModel.PROJECT_TYPE) String project,
            @ToolParam(description = "Optional filters, all must match", required = false)
            JqlFilter jql_filter,
            @ToolParam(description = "Words searched in summary, description and comments", required = false)
            @ArgumentLimits(minLength = 1, maxLength = MAX_TEXT)
            String text) {
        JqlFilter filter = jql_filter == null ? new JqlFilter(null, null, null) : jql_filter;
        String status = optional(filter.status(), NAME, "jql_filter.status must be a status name of 1-60 characters");
        String assignee = optional(filter.assignee(), USERNAME,
                "jql_filter.assignee must be a username of 1-100 characters");
        String type = optional(filter.type(), NAME, "jql_filter.type must be an issue type name of 1-60 characters");
        String words = searchText(text);
        QueryScope scope = policy.queryScope(JiraResourceModel.PROJECT_TYPE).narrowedTo(List.of(project));
        if (scope.linkIds().filter(Set::isEmpty).isPresent()) {
            return SearchView.of(project, new IssueSearchResult(List.of()));
        }
        String jql = scope.jql(SEARCH_FIELDS, SEARCH_ORDER_FIELDS)
                .where("status", Operator.EQUALS, status)
                .where("assignee", Operator.EQUALS, assignee)
                .where("text", Operator.CONTAINS, words)
                .orderBy("updated", Direction.DESC)
                .build();
        IssueSearchResult found = useCase.searchIssues(new IssueSearch(project, type, jql));
        return SearchView.of(project, visiblePage(found));
    }

    /**
     * The first {@link #MAX_SEARCH_RESULTS} hits the policy lets the caller see, and {@code hasMore} judged on those
     * alone: counting hits the caller may not see would tell, through the flag, that hidden issues exist. The hits are
     * judged in small batches (each result is cached for the call, the platform reuses it when it filters the result
     * itself), so a page rarely costs more lookups than it shows.
     */
    private IssueSearchResult visiblePage(IssueSearchResult found) {
        List<JiraIssue> visible = new ArrayList<>();
        List<JiraIssue> rows = found.issues();
        for (int from = 0; from < rows.size() && visible.size() <= MAX_SEARCH_RESULTS; from += MAX_SEARCH_RESULTS + 1) {
            List<JiraIssue> batch = rows.subList(from, Math.min(rows.size(), from + MAX_SEARCH_RESULTS + 1));
            visible.addAll(policy.retainAllowedById(JiraResourceModel.ISSUE_TYPE, batch, JiraIssue::key));
        }
        return new IssueSearchResult(visible.stream().limit(MAX_SEARCH_RESULTS).toList(),
                found.hasMore() || visible.size() > MAX_SEARCH_RESULTS);
    }

    @Tool(name = "add_comment", description = "Adds a comment to one Jira issue as the calling user. The text is "
            + "stored as plain text in a preformatted block; Jira markup in it is not interpreted. Returns the "
            + "key Jira knows the issue under (differs from the given one when the issue was moved), the id of "
            + "the new comment and its creation time.")
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
        if (body.isBlank() || hasControlCharacters(body, true)) {
            throw new InvalidToolArgumentException(
                    "body must be 1-10000 characters of text; line breaks and tabs are the only control characters allowed");
        }
        return CommentView.of(useCase.addComment(resolved(issueKey).key(), body));
    }

    @Tool(name = "transition_issue", description = "Moves one Jira issue through a workflow transition "
            + "available in its current status, given by transition id or name. A name shared by several "
            + "transitions is refused with the ids to choose from. Returns the key Jira knows the issue under, "
            + "the transition name and the status the issue moved to.")
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
        if (!(TRANSITION_ID.matcher(transition).matches() || NAME.matcher(transition).matches())) {
            throw new InvalidToolArgumentException("transition must be a transition id or a name of 1-60 characters");
        }
        return TransitionView.of(useCase.transitionIssue(resolved(issueKey).key(), transition));
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

    /**
     * The issue the resolver fetched for the policy: the one judged is the one returned, no second call to Jira, and
     * the key is the one Jira reports (the write goes to it, not to the requested key of a moved issue).
     */
    private static JiraIssue resolved(String issueKey) {
        return ResourceContext.current().orElseThrow()
                .fetched(JiraResourceModel.ISSUE_TYPE, issueKey, JiraIssue.class).orElseThrow();
    }

    private static String optional(String value, Pattern pattern, String message) {
        if (value != null && !pattern.matcher(value).matches()) {
            throw new InvalidToolArgumentException(message);
        }
        return value;
    }

    /** Words for Jira's text search with its query syntax removed; at least one letter or digit must remain. */
    private static String searchText(String text) {
        if (text == null) {
            return null;
        }
        String message = "text must be 1-200 characters without control characters and contain a letter or digit";
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
