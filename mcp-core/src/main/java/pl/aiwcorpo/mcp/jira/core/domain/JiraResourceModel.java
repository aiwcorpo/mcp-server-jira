package pl.aiwcorpo.mcp.jira.core.domain;

/**
 * How this server's records map to the resource types of the policy (C7): the issue is the resource of the tools,
 * the project is its container and the scope a search is narrowed to.
 */
public final class JiraResourceModel {

    public static final String ISSUE_TYPE = "Jira::Issue";
    public static final String PROJECT_TYPE = "Jira::Project";

    /**
     * Bounds of the key arguments. The pattern is the {@code idPattern} of the type in
     * {@code mcp/policies/resource-types.json}, which the starter publishes in the tool schema and checks on every
     * {@code @ResourceRef} value before a resolver sees it.
     */
    public static final int ISSUE_KEY_MAX_LENGTH = 21;
    public static final int PROJECT_KEY_MAX_LENGTH = 10;

    private JiraResourceModel() {
    }
}
