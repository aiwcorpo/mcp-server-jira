package pl.aiwcorpo.mcp.jira.core.domain;

/**
 * A Jira project as the resource {@code Jira::Project} of C7: its key and its category.
 *
 * @param key      project key as Jira reports it
 * @param category name of the project category in Jira, {@code ""} when the project has none (C7 requires the
 *                 attribute; the Cedar entity builder normalizes it)
 */
public record JiraProject(String key, String category) {
}
