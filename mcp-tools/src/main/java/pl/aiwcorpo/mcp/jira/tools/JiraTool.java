package pl.aiwcorpo.mcp.jira.tools;

import java.util.Map;
import java.util.regex.Pattern;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
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

/**
 * The MCP tools of this server. Each public {@code @Tool} method is a capability an AI client can call
 * through the gateway, so every argument is untrusted input from a model.
 *
 * <p>The gateway decides who may call a tool and asks for confirmations; the starter verifies that the
 * call came from the gateway for exactly this tool. What stays here: a risk flag on every tool, strict
 * argument checks before anything reaches Jira, and the resource always named by an explicit argument.
 *
 * <p>The input schema of each tool is {@link #inputSchemas()}, not the one Spring AI would derive: it closes
 * every object ({@code additionalProperties: false}) and states the length limits. The identifier patterns
 * are checked in code, so a wrong key is a C10 {@code invalid_argument} rather than the MCP SDK's own
 * schema error text.
 */
@Service
public class JiraTool {

    /** {@code JiraProjectKey} of mcp-contracts common.schema.json. */
    static final Pattern PROJECT_KEY = Pattern.compile("[A-Z][A-Z0-9_]{1,9}");
    /** {@code JiraIssueKey} of mcp-contracts common.schema.json. */
    static final Pattern ISSUE_KEY = Pattern.compile("[A-Z][A-Z0-9_]{1,9}-[1-9][0-9]{0,9}");
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

    static final int MAX_TEXT = 200;
    static final int MAX_BODY = 10_000;

    private static final Map<String, String> INPUT_SCHEMAS = Map.of(
            "get_issue", """
                    {"type":"object","additionalProperties":false,"required":["issueKey"],"properties":{
                     "issueKey":{"type":"string","minLength":4,"maxLength":21,
                      "description":"Issue key: project key, '-', number, e.g. ABC-12"}}}""",
            "search_issues", """
                    {"type":"object","additionalProperties":false,"required":["project"],"properties":{
                     "project":{"type":"string","minLength":2,"maxLength":10,
                      "description":"Project key, 2-10 upper-case letters, digits or '_', starting with a letter"},
                     "jql_filter":{"type":"object","additionalProperties":false,
                      "description":"Optional filters, all must match","properties":{
                       "status":{"type":"string","minLength":1,"maxLength":60,"description":"Status name"},
                       "assignee":{"type":"string","minLength":1,"maxLength":100,"description":"Assignee username"},
                       "type":{"type":"string","minLength":1,"maxLength":60,"description":"Issue type name"}}},
                     "text":{"type":"string","minLength":1,"maxLength":200,
                      "description":"Words searched in summary, description and comments"}}}""",
            "add_comment", """
                    {"type":"object","additionalProperties":false,"required":["issueKey","body"],"properties":{
                     "issueKey":{"type":"string","minLength":4,"maxLength":21,
                      "description":"Issue key: project key, '-', number, e.g. ABC-12"},
                     "body":{"type":"string","minLength":1,"maxLength":10000,
                      "description":"Comment text (Jira wiki markup)"}}}""",
            "transition_issue", """
                    {"type":"object","additionalProperties":false,"required":["issueKey","transition"],"properties":{
                     "issueKey":{"type":"string","minLength":4,"maxLength":21,
                      "description":"Issue key: project key, '-', number, e.g. ABC-12"},
                     "transition":{"type":"string","minLength":1,"maxLength":60,
                      "description":"Transition id or transition name available in the issue's current status"}}}""");

    private final JiraUseCase useCase;

    public JiraTool(JiraUseCase useCase) {
        this.useCase = useCase;
    }

    /** Input schema (JSON) of each tool, by tool name. */
    public static Map<String, String> inputSchemas() {
        return INPUT_SCHEMAS;
    }

    /** Filters of {@code search_issues}; each is optional. */
    public record JqlFilter(String status, String assignee, String type) {
    }

    @Tool(name = "get_issue", description = "Returns one Jira issue: key, project, type, status, assignee "
            + "username, last update time, and under untrustedText the summary and description as written by "
            + "Jira users (description cut at 10000 characters).")
    @ToolRisk(RiskLevel.READ_ONLY)
    public IssueView getIssue(@ToolParam(description = "Issue key") String issueKey) {
        return IssueView.of(useCase.getIssue(validIssueKey(issueKey)));
    }

    @Tool(name = "search_issues", description = "Searches issues of one Jira project, optionally filtered by "
            + "status, assignee, issue type and words in the text. Returns the total number of matches and at "
            + "most 25 issues, most recently updated first: key, type, status, assignee username, last update "
            + "time, and under untrustedText the summary as written by Jira users.")
    @ToolRisk(RiskLevel.READ_ONLY)
    public SearchView searchIssues(
            @ToolParam(description = "Project key") String project,
            @ToolParam(description = "Filters", required = false) JqlFilter jql_filter,
            @ToolParam(description = "Words to search", required = false) String text) {
        String projectKey = valid(project, PROJECT_KEY,
                "project must be a Jira project key: 2-10 upper-case letters, digits or '_', starting with a letter");
        JqlFilter filter = jql_filter == null ? new JqlFilter(null, null, null) : jql_filter;
        IssueSearch search = new IssueSearch(projectKey,
                optional(filter.status(), NAME, "jql_filter.status must be a status name of 1-60 characters"),
                optional(filter.assignee(), USERNAME, "jql_filter.assignee must be a username of 1-100 characters"),
                optional(filter.type(), NAME, "jql_filter.type must be an issue type name of 1-60 characters"),
                searchText(text));
        return SearchView.of(projectKey, useCase.searchIssues(search));
    }

    @Tool(name = "add_comment", description = "Adds a comment to one Jira issue as the calling user. Returns "
            + "the issue key, the id of the new comment and its creation time.")
    @ToolRisk(RiskLevel.UPDATE)
    public CommentView addComment(
            @ToolParam(description = "Issue key") String issueKey,
            @ToolParam(description = "Comment text") String body) {
        String key = validIssueKey(issueKey);
        if (body == null || body.isBlank() || body.length() > MAX_BODY || hasControlCharacters(body, true)) {
            throw new InvalidToolArgumentException(
                    "body must be 1-10000 characters of text; line breaks and tabs are the only control characters allowed");
        }
        return CommentView.of(useCase.addComment(key, body));
    }

    @Tool(name = "transition_issue", description = "Moves one Jira issue through a workflow transition "
            + "available in its current status, given by transition id or name. Returns the issue key, the "
            + "transition name and the status the issue moved to.")
    @ToolRisk(RiskLevel.UPDATE)
    public TransitionView transitionIssue(
            @ToolParam(description = "Issue key") String issueKey,
            @ToolParam(description = "Transition id or name") String transition) {
        String key = validIssueKey(issueKey);
        if (transition == null
                || !(TRANSITION_ID.matcher(transition).matches() || NAME.matcher(transition).matches())) {
            throw new InvalidToolArgumentException("transition must be a transition id or a name of 1-60 characters");
        }
        return TransitionView.of(key, useCase.transitionIssue(key, transition));
    }

    private static String validIssueKey(String issueKey) {
        return valid(issueKey, ISSUE_KEY,
                "issueKey must be a Jira issue key: project key, '-', number without leading zero, e.g. ABC-12");
    }

    private static String valid(String value, Pattern pattern, String message) {
        if (value == null || !pattern.matcher(value).matches()) {
            throw new InvalidToolArgumentException(message);
        }
        return value;
    }

    private static String optional(String value, Pattern pattern, String message) {
        return value == null ? null : valid(value, pattern, message);
    }

    /** Words for Jira's text search with its query syntax removed; at least one letter or digit must remain. */
    private static String searchText(String text) {
        if (text == null) {
            return null;
        }
        String message = "text must be 1-200 characters without control characters and contain a letter or digit";
        if (text.length() > MAX_TEXT || hasControlCharacters(text, false)) {
            throw new InvalidToolArgumentException(message);
        }
        String words = TEXT_SEARCH_SYNTAX.matcher(text).replaceAll(" ").trim().replaceAll("\\s+", " ");
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
