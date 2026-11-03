package pl.aiwcorpo.mcp.jira.core.domain;

import java.util.List;

/** The projects the caller may browse; {@code truncated} when Jira listed more than the server reads. */
public record ProjectList(List<ProjectSummary> projects, boolean truncated) {

    public ProjectList {
        projects = List.copyOf(projects);
    }
}
