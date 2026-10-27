package pl.aiwcorpo.mcp.jira.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import pl.aiwcorpo.mcp.jira.core.domain.JiraRecord;
import pl.aiwcorpo.mcp.jira.core.domain.RecordNotFoundException;

class JiraServiceTest {

    private final Map<String, JiraRecord> backend = Map.of("ABC-1", new JiraRecord("ABC-1", "ABC", "First", "open"));
    private final JiraService service = new JiraService(id -> Optional.ofNullable(backend.get(id)));

    @Test
    void returnsTheRecordTheBackendShows() {
        assertThat(service.getRecord("ABC-1").title()).isEqualTo("First");
    }

    @Test
    void aRecordTheBackendDoesNotShowIsNotFoundOrDenied() {
        assertThatThrownBy(() -> service.getRecord("ABC-2")).isInstanceOf(RecordNotFoundException.class);
    }
}
