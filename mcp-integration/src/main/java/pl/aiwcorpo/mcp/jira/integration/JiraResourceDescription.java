package pl.aiwcorpo.mcp.jira.integration;

import java.util.HashMap;
import java.util.Map;
import pl.aiwcorpo.mcp.cedar.entities.ResolvedResource;
import pl.aiwcorpo.mcp.jira.core.domain.JiraIssue;
import pl.aiwcorpo.mcp.jira.core.domain.JiraProject;
import pl.aiwcorpo.mcp.jira.core.domain.JiraResourceModel;

/**
 * The issue and the project as the policy sees them (C7, entity-builder.md). Id and parent come from Jira's answer,
 * never from the tool argument: an issue moved to a restricted project is judged by the project it is in now.
 * {@code Jira::Project.category} is required, {@code ""} meaning "no category"; a project whose category was not
 * looked up gets no attribute, and the builder refuses it (fail closed).
 */
final class JiraResourceDescription {

    private JiraResourceDescription() {
    }

    static ResolvedResource of(JiraIssue issue) {
        return ResolvedResource.of(JiraResourceModel.ISSUE_TYPE, issue.key(), Map.of("key", issue.key()))
                .withParent(project(issue.projectKey(), issue.projectCategory()));
    }

    static ResolvedResource of(JiraProject project) {
        return project(project.key(), project.category());
    }

    private static ResolvedResource project(String key, String category) {
        Map<String, Object> attributes = new HashMap<>();
        attributes.put("key", key);
        attributes.put("category", category);
        return ResolvedResource.of(JiraResourceModel.PROJECT_TYPE, key, attributes);
    }
}
