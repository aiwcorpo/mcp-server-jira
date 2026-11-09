package pl.aiwcorpo.mcp.jira.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doThrow;

import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.spec.McpSchema;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import pl.aiwcorpo.mcp.jira.core.domain.BackendDegradedException;
import pl.aiwcorpo.mcp.jira.tools.JiraWriteGate;

/** A Jira version the server was not tested with: every tool that changes something refuses, reads go on. */
class DegradedModeTest extends ServerTestSupport {

    @MockitoBean
    JiraWriteGate gate;

    @BeforeEach
    void degrade() {
        BACKEND.reset();
        doThrow(new BackendDegradedException()).when(gate).requireWritable();
    }

    @Test
    void everyChangeIsRefusedAsUpstreamUnavailableAndNothingIsWritten() {
        Map<String, Map<String, Object>> changes = new java.util.LinkedHashMap<>();
        changes.put("add_comment", Map.of("issueKey", "ABC-1", "body", "x"));
        changes.put("edit_comment", Map.of("issueKey", "ABC-1", "commentId", "10500", "body", "x"));
        changes.put("transition_issue", Map.of("issueKey", "ABC-1", "transition", "21"));
        changes.put("create_issue", Map.of("project", "ABC", "issueType", "Task", "summary", "x"));
        changes.put("update_issue", Map.of("issueKey", "ABC-1", "summary", "x"));
        changes.put("assign_issue", Map.of("issueKey", "ABC-1", "assignee", "alice"));
        changes.put("unassign_issue", Map.of("issueKey", "ABC-1"));
        changes.put("link_issues", Map.of("outwardIssueKey", "ABC-1", "inwardIssueKey", "DEF-5", "linkType", "Blocks"));
        changes.put("add_worklog", Map.of("issueKey", "ABC-1", "timeSpent", "1h"));
        changes.put("add_watcher", Map.of("issueKey", "ABC-1"));
        changes.put("unwatch_issue", Map.of("issueKey", "ABC-1"));
        try (McpSyncClient client = clientWithCredential(PAT);
                McpSyncClient eraser = clientWithToken(ServerTestSupport::eraser, PAT)) {
            changes.forEach((tool, arguments) -> {
                McpSchema.CallToolResult result = call(client, tool, arguments);
                assertThat(result.isError()).as(tool).isTrue();
                assertThat(text(result)).as(tool).contains("\"error\":\"upstream_unavailable\"", "Nothing was changed");
            });
            McpSchema.CallToolResult deletion = call(eraser, "delete_issue", Map.of("issueKey", "ABC-1"));
            assertThat(text(deletion)).contains("\"error\":\"upstream_unavailable\"");
            McpSchema.CallToolResult unlink = call(eraser, "remove_issue_link",
                    Map.of("outwardIssueKey", "ABC-1", "inwardIssueKey", "ABC-2", "linkId", "90001"));
            assertThat(text(unlink)).contains("\"error\":\"upstream_unavailable\"");
        }
        assertThat(BACKEND.writes()).isEmpty();
    }

    @Test
    void readsKeepWorking() {
        try (McpSyncClient client = clientWithCredential(PAT)) {
            assertThat(call(client, "get_issue", Map.of("issueKey", "ABC-1")).isError()).isFalse();
            assertThat(call(client, "search_issues", Map.of("project", "ABC")).isError()).isFalse();
            assertThat(call(client, "list_comments", Map.of("issueKey", "ABC-1")).isError()).isFalse();
            assertThat(call(client, "get_project", Map.of("project", "ABC")).isError()).isFalse();
        }
        assertThat(List.copyOf(BACKEND.requests())).isNotEmpty();
    }
}
