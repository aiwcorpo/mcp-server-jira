package pl.aiwcorpo.mcp.jira.app.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import pl.aiwcorpo.mcp.jira.application.service.JiraService;
import pl.aiwcorpo.mcp.jira.core.domain.ObservedVersions;
import pl.aiwcorpo.mcp.jira.core.port.in.JiraUseCase;
import pl.aiwcorpo.mcp.jira.core.port.out.JiraResourcePort;

/** Assembles the hexagon: framework-free use cases from mcp-application, adapters from mcp-integration. */
@Configuration
public class ApplicationWiringConfiguration {

    @Bean
    ObservedVersions observedVersions() {
        return new ObservedVersions();
    }

    @Bean
    JiraUseCase jiraUseCase(JiraResourcePort resource) {
        return new JiraService(resource);
    }
}
