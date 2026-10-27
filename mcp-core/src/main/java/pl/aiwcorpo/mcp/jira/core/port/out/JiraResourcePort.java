package pl.aiwcorpo.mcp.jira.core.port.out;

import java.util.Optional;
import pl.aiwcorpo.mcp.jira.core.domain.JiraRecord;

/** Outbound port to the backend system, implemented in mcp-integration. */
public interface JiraResourcePort {

    /** Empty when the backend does not show the record to the caller, whether it exists or not. */
    Optional<JiraRecord> findById(String recordId);
}
