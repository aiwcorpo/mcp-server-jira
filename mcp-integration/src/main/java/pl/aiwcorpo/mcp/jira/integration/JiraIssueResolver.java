package pl.aiwcorpo.mcp.jira.integration;

import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import org.springframework.stereotype.Component;
import pl.aiwcorpo.mcp.cedar.entities.ResolvedResource;
import pl.aiwcorpo.mcp.jira.core.domain.JiraIssue;
import pl.aiwcorpo.mcp.jira.core.domain.JiraProject;
import pl.aiwcorpo.mcp.jira.core.domain.JiraResourceModel;
import pl.aiwcorpo.mcp.jira.core.port.out.JiraResourcePort;
import pl.aiwcorpo.mcp.platform.resource.ResourceResolver;

/**
 * Tells the starter what an issue key is, before the tool runs and for every hit of a search: Jira is asked with the
 * caller's own credential, and the project of the answer is looked up for its category, which the policy classifies
 * by. The key and the project in the description are the ones Jira reports, so a moved issue is judged by the
 * project it is in now.
 *
 * <p>An issue Jira does not show, or whose project it does not show, is empty (one {@code not_found_or_denied}); an
 * issue without a project cannot be classified and is refused as unusable. A Jira that fails throws.
 */
@Component
public class JiraIssueResolver implements ResourceResolver<JiraIssue> {

    private final JiraResourcePort jira;

    public JiraIssueResolver(JiraResourcePort jira) {
        this.jira = jira;
    }

    @Override
    public String resourceType() {
        return JiraResourceModel.ISSUE_TYPE;
    }

    @Override
    public Optional<JiraIssue> fetch(String issueKey) {
        return withCategory(issueKey, new HashMap<>());
    }

    /**
     * The hits of one search mostly share a project, so each project is looked up once per list. An element Jira
     * answers unusably is left out (and so removed from the result) instead of failing the whole search.
     */
    @Override
    public Map<String, JiraIssue> fetchAll(Collection<String> issueKeys) {
        Map<String, Optional<JiraProject>> projects = new HashMap<>();
        Map<String, JiraIssue> found = new LinkedHashMap<>();
        for (String issueKey : issueKeys) {
            try {
                withCategory(issueKey, projects).ifPresent(issue -> found.put(issueKey, issue));
            } catch (BackendResponseException unusable) {
                // Not in the map: the starter drops the element, and the rest of the list is judged as usual.
            }
        }
        return found;
    }

    @Override
    public ResolvedResource describe(JiraIssue issue) {
        return JiraResourceDescription.of(issue);
    }

    private Optional<JiraIssue> withCategory(String issueKey, Map<String, Optional<JiraProject>> projects) {
        Optional<JiraIssue> issue = jira.findIssue(issueKey);
        if (issue.isEmpty()) {
            return Optional.empty();
        }
        String projectKey = issue.get().projectKey();
        if (projectKey == null || projectKey.isBlank()) {
            throw new BackendResponseException("issue without project");
        }
        return projects.computeIfAbsent(projectKey, jira::findProject)
                .map(project -> issue.get().withProjectCategory(project.category()));
    }
}
