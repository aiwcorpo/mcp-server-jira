package pl.aiwcorpo.mcp.jira.application.service;

import pl.aiwcorpo.mcp.jira.core.domain.JiraRecord;
import pl.aiwcorpo.mcp.jira.core.domain.RecordNotFoundException;
import pl.aiwcorpo.mcp.jira.core.port.in.JiraUseCase;
import pl.aiwcorpo.mcp.jira.core.port.out.JiraResourcePort;

/** Use cases of the server. A plain class: mcp-app makes it a bean, so this module stays free of Spring. */
public class JiraService implements JiraUseCase {

    private final JiraResourcePort resource;

    public JiraService(JiraResourcePort resource) {
        this.resource = resource;
    }

    @Override
    public JiraRecord getRecord(String recordId) {
        return resource.findById(recordId).orElseThrow(RecordNotFoundException::new);
    }
}
