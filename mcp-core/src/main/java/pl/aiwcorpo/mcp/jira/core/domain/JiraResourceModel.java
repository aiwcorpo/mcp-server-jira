package pl.aiwcorpo.mcp.jira.core.domain;

/**
 * How this server's records map to the resource types of the policy (C7): the issue is the resource of the tools,
 * the project is its container and the scope a virtual server link narrows.
 */
public final class JiraResourceModel {

    public static final String ISSUE_TYPE = "Jira::Issue";
    public static final String PROJECT_TYPE = "Jira::Project";

    /**
     * Contract patterns (common.schema.json) and bounds of the key arguments; the starter also checks every
     * {@code @ResourceRef} value against the pattern of its type before a resolver sees it.
     */
    public static final String ISSUE_KEY_CONTRACT = "JiraIssueKey";
    public static final int ISSUE_KEY_MAX_LENGTH = 21;
    public static final String PROJECT_KEY_CONTRACT = "JiraProjectKey";
    public static final int PROJECT_KEY_MAX_LENGTH = 10;

    private JiraResourceModel() {
    }
}
