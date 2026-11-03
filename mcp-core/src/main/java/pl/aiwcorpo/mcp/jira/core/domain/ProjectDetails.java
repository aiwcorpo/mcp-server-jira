package pl.aiwcorpo.mcp.jira.core.domain;

import java.util.List;

/**
 * What a project offers to someone creating or filing issues. All names are set by Jira administrators.
 *
 * @param category   {@code ""} when the project has none
 * @param issueTypes names of the issue types that can be created in the project
 * @param components component names
 * @param versions   versions with their release state
 */
public record ProjectDetails(String key, String name, String category, List<String> issueTypes,
        List<String> components, List<Version> versions) {

    public record Version(String name, boolean released) {
    }

    public ProjectDetails {
        issueTypes = List.copyOf(issueTypes);
        components = List.copyOf(components);
        versions = List.copyOf(versions);
    }
}
