package pl.aiwcorpo.mcp.jira.app;

import static org.assertj.core.api.Assertions.assertThat;

import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.spec.McpSchema;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** The public surface: exactly the four tools the gateway has in its configuration, each with a closed schema. */
class ToolListTest extends ServerTestSupport {

    @Test
    void exactlyTheConfiguredToolsWithTheirRisk() {
        try (McpSyncClient client = clientWithCredential(PAT)) {
            List<McpSchema.Tool> tools = client.listTools().tools();

            assertThat(tools).extracting(McpSchema.Tool::name)
                    .containsExactlyInAnyOrder("search_issues", "get_issue", "add_comment", "transition_issue");
            assertThat(tools).filteredOn(t -> t.annotations().readOnlyHint()).extracting(McpSchema.Tool::name)
                    .containsExactlyInAnyOrder("search_issues", "get_issue");
            assertThat(tools).noneMatch(t -> Boolean.TRUE.equals(t.annotations().destructiveHint()));
        }
    }

    @Test
    void everyObjectInEveryInputSchemaIsClosedAndEveryStringIsBounded() {
        try (McpSyncClient client = clientWithCredential(PAT)) {
            for (McpSchema.Tool tool : client.listTools().tools()) {
                assertClosed(tool.name(), tool.inputSchema());
            }
        }
    }

    @SuppressWarnings("unchecked")
    private static void assertClosed(String tool, Map<String, Object> schema) {
        assertThat(schema).as(tool).containsEntry("additionalProperties", false);
        ((Map<String, Map<String, Object>>) schema.get("properties")).forEach((name, property) -> {
            if ("object".equals(property.get("type"))) {
                assertClosed(tool + "." + name, property);
            } else {
                assertThat(property).as(tool + "." + name).containsEntry("type", "string").containsKey("maxLength");
            }
        });
    }
}
