package pl.aiwcorpo.mcp.jira.app;

import static org.assertj.core.api.Assertions.assertThat;

import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.spec.McpSchema;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** The public surface: exactly the five tools the gateway has in its configuration, each with a closed schema. */
class ToolListTest extends ServerTestSupport {

    @Test
    void exactlyTheConfiguredToolsWithTheirRisk() {
        try (McpSyncClient client = clientWithCredential(PAT)) {
            List<McpSchema.Tool> tools = client.listTools().tools();

            assertThat(tools).extracting(McpSchema.Tool::name)
                    .containsExactlyInAnyOrder("search_issues", "get_issue", "add_comment", "transition_issue", "delete_issue");
            assertThat(tools).filteredOn(t -> t.annotations().readOnlyHint()).extracting(McpSchema.Tool::name)
                    .containsExactlyInAnyOrder("search_issues", "get_issue");
            assertThat(tools).filteredOn(t -> Boolean.TRUE.equals(t.annotations().destructiveHint()))
                    .extracting(McpSchema.Tool::name).containsExactly("delete_issue");
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

    @Test
    @SuppressWarnings("unchecked")
    void keysCarryThePatternsOfTheContractsAndTheFilterIsBounded() {
        McpSchema.Tool getIssue = listedTool("get_issue");
        Map<String, Object> issueKey = ((Map<String, Map<String, Object>>) getIssue.inputSchema().get("properties"))
                .get("issueKey");
        assertThat(issueKey).containsEntry("pattern", "^[A-Z][A-Z0-9_]{1,9}-[1-9][0-9]{0,9}$")
                .containsEntry("maxLength", 21);

        Map<String, Map<String, Object>> search =
                (Map<String, Map<String, Object>>) listedTool("search_issues").inputSchema().get("properties");
        assertThat(search.get("project")).containsEntry("pattern", "^[A-Z][A-Z0-9_]{1,9}$");
        Map<String, Object> filter = search.get("jql_filter");
        assertThat(filter).containsEntry("additionalProperties", false).doesNotContainKey("required");
        ((Map<String, Map<String, Object>>) filter.get("properties")).forEach((name, property) ->
                assertThat(property).as(name).containsKeys("minLength", "maxLength"));
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
