package pl.aiwcorpo.mcp.jira.integration;

import java.util.Optional;
import org.springframework.stereotype.Component;
import pl.aiwcorpo.mcp.cedar.entities.ResolvedResource;
import pl.aiwcorpo.mcp.jira.core.domain.JiraProject;
import pl.aiwcorpo.mcp.jira.core.domain.JiraResourceModel;
import pl.aiwcorpo.mcp.jira.core.port.out.JiraResourcePort;
import pl.aiwcorpo.mcp.platform.resource.ResourceResolver;

/**
 * Tells the starter what a project key is, before the search runs: Jira is asked with the caller's own credential
 * and the answer is described with the project category the policy classifies by. Empty for a project that does not
 * exist and for one the caller may not see; a Jira that fails throws, which the client sees as
 * {@code upstream_unavailable}, not as a denial.
 */
@Component
public class JiraProjectResolver implements ResourceResolver<JiraProject> {

    private final JiraResourcePort jira;

    public JiraProjectResolver(JiraResourcePort jira) {
        this.jira = jira;
    }

    @Override
    public String resourceType() {
        return JiraResourceModel.PROJECT_TYPE;
    }

    @Override
    public Optional<JiraProject> fetch(String projectKey) {
        return jira.findProject(projectKey);
    }

    @Override
    public ResolvedResource describe(JiraProject project) {
        return JiraResourceDescription.of(project);
    }
}
