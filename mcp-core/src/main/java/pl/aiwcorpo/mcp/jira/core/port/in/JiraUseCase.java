package pl.aiwcorpo.mcp.jira.core.port.in;

import pl.aiwcorpo.mcp.jira.core.domain.JiraRecord;

/** Inbound port: what the server can do, in business terms. Implemented in mcp-application, called by mcp-tools. */
public interface JiraUseCase {

    /**
     * @throws pl.aiwcorpo.mcp.jira.core.domain.RecordNotFoundException when the record does not exist or
     *         the caller's backend account may not see it
     */
    JiraRecord getRecord(String recordId);
}
