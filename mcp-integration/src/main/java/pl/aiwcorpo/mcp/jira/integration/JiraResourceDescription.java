package pl.aiwcorpo.mcp.jira.integration;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import pl.aiwcorpo.mcp.cedar.entities.ResolvedResource;
import pl.aiwcorpo.mcp.cedar.entities.Scopes;
import pl.aiwcorpo.mcp.jira.core.domain.JiraIssue;
import pl.aiwcorpo.mcp.jira.core.domain.JiraProject;
import pl.aiwcorpo.mcp.jira.core.domain.JiraResourceModel;

/**
 * The issue and the project as the policy sees them (C7, entity-builder.md). Id and parent come from Jira's answer,
 * never from the tool argument: an issue moved to a restricted project is judged by the project it is in now.
 * {@code Jira::Project.category} is required, {@code ""} meaning "no category"; a project whose category was not
 * looked up gets no attribute, and the builder refuses it (fail closed). Both types carry the one scope of their
 * project ({@code Jira::Project/<key>}): the policies grant changes with {@code principal in resource.scopes}.
 */
final class JiraResourceDescription {

    private JiraResourceDescription() {
    }

    static ResolvedResource of(JiraIssue issue) {
        return issueOf(issue.key(), project(issue.projectKey(), issue.projectCategory()));
    }

    static ResolvedResource of(JiraProject project) {
        return project(project.key(), project.category());
    }

    /**
     * The project as its key alone describes it, with the least restrictive category, so that the grant can be judged
     * before Jira is asked (CC-146).
     */
    static Optional<ResolvedResource> probeOfProject(String projectKey) {
        return Optional.of(project(projectKey, ""));
    }

    /** The issue as its key alone describes it: the project is the part of the key before the number. */
    static Optional<ResolvedResource> probeOfIssue(String issueKey) {
        int dash = issueKey.lastIndexOf('-');
        if (dash <= 0) {
            return Optional.empty();
        }
        return Optional.of(issueOf(issueKey, project(issueKey.substring(0, dash), "")));
    }

    private static ResolvedResource issueOf(String key, ResolvedResource project) {
        return ResolvedResource.of(JiraResourceModel.ISSUE_TYPE, key, Map.of("key", key))
                .withParent(project)
                .withScopes(project.scopes());
    }

    private static ResolvedResource project(String key, String category) {
        Map<String, Object> attributes = new HashMap<>();
        attributes.put("key", key);
        attributes.put("category", category);
        return ResolvedResource.of(JiraResourceModel.PROJECT_TYPE, key, attributes)
                .withScopes(List.of(Scopes.id(JiraResourceModel.PROJECT_TYPE, key)));
    }
}
