package pl.aiwcorpo.mcp.jira.core.domain;

/**
 * A Jira project as the resource {@code Jira::Project} of C7: its key and its category, plus what the same answer
 * says about the project, so a tool that shows it uses the object the policy judged and not a second read.
 *
 * @param key      project key as Jira reports it (the current key, also when the request used an earlier one)
 * @param category name of the project category in Jira, {@code ""} when the project has none (C7 requires the
 *                 attribute; the Cedar entity builder normalizes it)
 * @param details  name, issue types, components and versions from the same response
 */
public record JiraProject(String key, String category, ProjectDetails details) {

    public JiraProject(String key, String category) {
        this(key, category, new ProjectDetails(key, "", category, java.util.List.of(), java.util.List.of(), java.util.List.of()));
    }
}
