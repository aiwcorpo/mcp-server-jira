package pl.aiwcorpo.mcp.jira.tools;

import java.util.regex.Pattern;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Service;
import pl.aiwcorpo.mcp.jira.core.domain.IssueSearch;
import pl.aiwcorpo.mcp.jira.core.port.in.JiraUseCase;
import pl.aiwcorpo.mcp.jira.tools.JiraToolViews.CommentView;
import pl.aiwcorpo.mcp.jira.tools.JiraToolViews.IssueView;
import pl.aiwcorpo.mcp.jira.tools.JiraToolViews.SearchView;
import pl.aiwcorpo.mcp.jira.tools.JiraToolViews.TransitionView;
import pl.aiwcorpo.mcp.platform.error.InvalidToolArgumentException;
import pl.aiwcorpo.mcp.platform.guard.RiskLevel;
import pl.aiwcorpo.mcp.platform.guard.ToolRisk;
import pl.aiwcorpo.mcp.platform.input.ArgumentLimits;

/**
 * The MCP tools of this server. Each public {@code @Tool} method is a capability an AI client can call
 * through the gateway, so every argument is untrusted input from a model.
 *
 * <p>The gateway decides who may call a tool and asks for confirmations; the starter verifies that the call
 * came from the gateway for exactly this tool and checks the arguments against the input schema before a
 * method runs ({@link ArgumentLimits}, published by {@code SchemaLimitedToolCallbacks}): closed objects,
 * lengths and the key patterns of mcp-contracts. What stays here: a risk flag on every tool, the checks the
 * schema does not express (names, usernames, search text) and the resource always named by an explicit
 * argument.
 */
@Service
public class JiraTool {

    /** Status, issue type and transition names: letters, digits, space and a little punctuation. */
    static final Pattern NAME = Pattern.compile("[\\p{L}\\p{N}][\\p{L}\\p{N} _./()&'-]{0,59}");
    /** Jira Data Center usernames. */
    static final Pattern USERNAME = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._@+-]{0,99}");
    static final Pattern TRANSITION_ID = Pattern.compile("[1-9][0-9]{0,9}");
    /**
     * Characters with a meaning in Jira's text search (Lucene syntax). They are replaced by spaces, which the
     * text index treats as separators anyway, so the words are searched literally.
     */
    static final Pattern TEXT_SEARCH_SYNTAX = Pattern.compile("[+\\-&|!(){}\\[\\]^~*?\\\\:\"/]");
    /** Boolean operators of the same syntax (upper case only); left in, a lone one makes Jira answer 400. */
    static final Pattern TEXT_SEARCH_OPERATOR = Pattern.compile("\\b(?:AND|OR|NOT)\\b");

    /** Longest {@code JiraProjectKey} / {@code JiraIssueKey} of the contracts (the definitions give no length). */
    static final int PROJECT_KEY_MAX_LENGTH = 10;
    static final int ISSUE_KEY_MAX_LENGTH = 21;
    public static final int MAX_NAME = 60;
    public static final int MAX_USERNAME = 100;
    static final int MAX_TEXT = 200;
    static final int MAX_BODY = 10_000;

    private final JiraUseCase useCase;

    public JiraTool(JiraUseCase useCase) {
        this.useCase = useCase;
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
    public IssueView getIssue(
            @ToolParam(description = "Issue key: project key, '-', number, e.g. ABC-12")
            @ArgumentLimits(minLength = 4, maxLength = ISSUE_KEY_MAX_LENGTH, contractPattern = "JiraIssueKey")
            String issueKey) {
        return IssueView.of(useCase.getIssue(issueKey));
    }

    @Tool(name = "search_issues", description = "Searches issues of one Jira project, optionally filtered by "
            + "status, assignee, issue type and words in the text. Returns the total number of matches and at "
            + "most 25 issues, most recently updated first: key, type, status, assignee username, last update "
            + "time and the summary as written by Jira users, marked as untrusted data.")
    @ToolRisk(RiskLevel.READ_ONLY)
    public SearchView searchIssues(
            @ToolParam(description = "Project key, 2-10 upper-case letters, digits or '_', starting with a letter")
            @ArgumentLimits(minLength = 2, maxLength = PROJECT_KEY_MAX_LENGTH, contractPattern = "JiraProjectKey")
            String project,
            @ToolParam(description = "Optional filters, all must match", required = false)
            JqlFilter jql_filter,
            @ToolParam(description = "Words searched in summary, description and comments", required = false)
            @ArgumentLimits(minLength = 1, maxLength = MAX_TEXT)
            String text) {
        JqlFilter filter = jql_filter == null ? new JqlFilter(null, null, null) : jql_filter;
        IssueSearch search = new IssueSearch(project,
                optional(filter.status(), NAME, "jql_filter.status must be a status name of 1-60 characters"),
                optional(filter.assignee(), USERNAME, "jql_filter.assignee must be a username of 1-100 characters"),
                optional(filter.type(), NAME, "jql_filter.type must be an issue type name of 1-60 characters"),
                searchText(text));
        return SearchView.of(project, useCase.searchIssues(search));
    }

    @Tool(name = "add_comment", description = "Adds a comment to one Jira issue as the calling user. The text is "
            + "stored as plain text in a preformatted block; Jira markup in it is not interpreted. Returns the "
            + "key Jira knows the issue under (differs from the given one when the issue was moved), the id of "
            + "the new comment and its creation time.")
    @ToolRisk(RiskLevel.UPDATE)
    public CommentView addComment(
            @ToolParam(description = "Issue key: project key, '-', number, e.g. ABC-12")
            @ArgumentLimits(minLength = 4, maxLength = ISSUE_KEY_MAX_LENGTH, contractPattern = "JiraIssueKey")
            String issueKey,
            @ToolParam(description = "Comment text, plain text")
            @ArgumentLimits(minLength = 1, maxLength = MAX_BODY)
            String body) {
        if (body.isBlank() || hasControlCharacters(body, true)) {
            throw new InvalidToolArgumentException(
                    "body must be 1-10000 characters of text; line breaks and tabs are the only control characters allowed");
        }
        return CommentView.of(useCase.addComment(issueKey, body));
    }

    @Tool(name = "transition_issue", description = "Moves one Jira issue through a workflow transition "
            + "available in its current status, given by transition id or name. A name shared by several "
            + "transitions is refused with the ids to choose from. Returns the key Jira knows the issue under, "
            + "the transition name and the status the issue moved to.")
    @ToolRisk(RiskLevel.UPDATE)
    public TransitionView transitionIssue(
            @ToolParam(description = "Issue key: project key, '-', number, e.g. ABC-12")
            @ArgumentLimits(minLength = 4, maxLength = ISSUE_KEY_MAX_LENGTH, contractPattern = "JiraIssueKey")
            String issueKey,
            @ToolParam(description = "Transition id, or transition name available in the issue's current status")
            @ArgumentLimits(minLength = 1, maxLength = MAX_NAME)
            String transition) {
        if (!(TRANSITION_ID.matcher(transition).matches() || NAME.matcher(transition).matches())) {
            throw new InvalidToolArgumentException("transition must be a transition id or a name of 1-60 characters");
        }
        return TransitionView.of(useCase.transitionIssue(issueKey, transition));
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
