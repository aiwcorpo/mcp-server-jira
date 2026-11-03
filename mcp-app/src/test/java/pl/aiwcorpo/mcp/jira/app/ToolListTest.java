package pl.aiwcorpo.mcp.jira.app;

import static org.assertj.core.api.Assertions.assertThat;

import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.spec.McpSchema;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** The public surface: exactly the tools the gateway has in its configuration, each with a closed schema. */
class ToolListTest extends ServerTestSupport {

    static final List<String> READ_TOOLS = List.of("search_issues", "get_issue", "list_comments", "list_transitions",
            "get_project", "list_projects", "find_assignable_users", "list_worklogs", "list_attachments");
    static final List<String> UPDATE_TOOLS = List.of("add_comment", "edit_comment", "transition_issue", "create_issue",
            "update_issue", "assign_issue", "unassign_issue", "link_issues", "add_worklog");
    static final List<String> READ_TOOLS_AND_WRITE_TOOLS = java.util.stream.Stream
            .concat(java.util.stream.Stream.concat(READ_TOOLS.stream(), UPDATE_TOOLS.stream()), java.util.stream.Stream.of("delete_issue"))
            .toList();

    @Test
    void exactlyTheConfiguredToolsWithTheirRisk() {
        try (McpSyncClient client = clientWithCredential(PAT)) {
            List<McpSchema.Tool> tools = client.listTools().tools();

            assertThat(tools).extracting(McpSchema.Tool::name).containsExactlyInAnyOrderElementsOf(READ_TOOLS_AND_WRITE_TOOLS);
            assertThat(tools).filteredOn(t -> t.annotations().readOnlyHint()).extracting(McpSchema.Tool::name)
                    .containsExactlyInAnyOrderElementsOf(READ_TOOLS);
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
        assertThat(((Map<String, Object>) filter.get("properties")).keySet())
                .containsExactlyInAnyOrder("status", "assignee", "type", "summary");
        ((Map<String, Map<String, Object>>) filter.get("properties")).forEach((name, property) ->
                assertThat(property).as(name).containsKeys("minLength", "maxLength"));
    }

    @SuppressWarnings("unchecked")
    private static void assertClosed(String tool, Map<String, Object> schema) {
        assertThat(schema).as(tool).containsEntry("additionalProperties", false);
        ((Map<String, Map<String, Object>>) schema.get("properties")).forEach((name, property) -> {
            if ("object".equals(property.get("type"))) {
                assertClosed(tool + "." + name, property);
            } else if ("array".equals(property.get("type"))) {
                assertThat(property).as(tool + "." + name).containsKey("maxItems");
                assertThat((Map<String, Object>) property.get("items")).as(tool + "." + name + " items")
                        .containsEntry("type", "string").containsKey("maxLength");
            } else {
                assertThat(property).as(tool + "." + name).containsEntry("type", "string").containsKey("maxLength");
            }
        });
    }

    @Test
    @SuppressWarnings("unchecked")
    void listsTakeAnOpaqueCursorAndNoOffset() {
        for (String tool : List.of("search_issues", "list_comments", "list_worklogs", "list_projects")) {
            Map<String, Map<String, Object>> properties =
                    (Map<String, Map<String, Object>>) listedTool(tool).inputSchema().get("properties");
            assertThat(properties).as(tool).containsKey("cursor").doesNotContainKeys("startAt", "offset", "page", "limit");
            assertThat(properties.get("cursor")).as(tool).containsEntry("maxLength", 1024)
                    .containsEntry("pattern", "^[A-Za-z0-9_.-]{1,1024}$");
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    void noToolTakesQueryTextOfAnySystem() {
        try (McpSyncClient client = clientWithCredential(PAT)) {
            for (McpSchema.Tool tool : client.listTools().tools()) {
                assertThat(((Map<String, Object>) tool.inputSchema().get("properties")).keySet())
                        .as(tool.name()).doesNotContain("jql", "cql", "query_dsl", "filter_query");
            }
        }
    }
}
