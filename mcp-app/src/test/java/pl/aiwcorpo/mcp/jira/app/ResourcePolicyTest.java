package pl.aiwcorpo.mcp.jira.app;

import static org.assertj.core.api.Assertions.assertThat;

import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.spec.McpSchema;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The policy (C7) on the resources of the tools, with the example bundle of the contracts: an issue is judged by the
 * project Jira says it is in, whatever the key looks like, and every refusal looks like a missing issue.
 */
class ResourcePolicyTest extends ServerTestSupport {

    private static final String NOT_FOUND_OR_DENIED = "\"error\":\"not_found_or_denied\"";

    @BeforeEach
    void resetBackend() {
        BACKEND.reset();
    }

    @Test
    void everyToolOnARestrictedIssueAnswersLikeOnAMissingOne() {
        try (McpSyncClient client = clientWithCredential(PAT)) {
            Map<String, Map<String, Object>> calls = Map.of(
                    "get_issue", Map.of("issueKey", "%s"),
                    "add_comment", Map.of("issueKey", "%s", "body", "x"),
                    "transition_issue", Map.of("issueKey", "%s", "transition", "21"));
            calls.forEach((tool, arguments) -> {
                String missing = text(call(client, tool, with(arguments, "ABC-999")));
                McpSchema.CallToolResult restricted = call(client, tool, with(arguments, "HR-1"));
                McpSchema.CallToolResult moved = call(client, tool, with(arguments, "ABC-30"));

                assertThat(restricted.isError()).as(tool).isTrue();
                assertThat(text(restricted)).as(tool).contains(NOT_FOUND_OR_DENIED).isEqualTo(missing);
                assertThat(text(moved)).as(tool + " on an issue moved to the restricted project").isEqualTo(missing);
            });
        }
        assertThat(BACKEND.writes()).isEmpty();
    }

    @Test
    void aSearchOfARestrictedProjectLooksLikeASearchOfAMissingOne() {
        try (McpSyncClient client = clientWithCredential(PAT)) {
            McpSchema.CallToolResult missing = call(client, "search_issues", Map.of("project", "NOPE"));
            McpSchema.CallToolResult restricted = call(client, "search_issues", Map.of("project", "HR"));

            assertThat(text(restricted)).contains(NOT_FOUND_OR_DENIED).isEqualTo(text(missing));
        }
        assertThat(BACKEND.searches()).isEmpty();
    }

    @Test
    void hitsThatTheResolverFindsRestrictedOrUnreadableAreRemovedFromTheList() {
        try (McpSyncClient client = clientWithCredential(PAT)) {
            McpSchema.CallToolResult result = call(client, "search_issues",
                    Map.of("project", "ABC", "text", "stale index"));

            assertThat(result.isError()).isFalse();
            assertThat(text(result)).contains("ABC-1", "ABC-2")
                    .doesNotContain("ABC-30", "Payroll", "ABC-500", "Unreadable", "Salary");
        }
    }

    @Test
    void aWriteGroupWithoutTheProjectScopeCannotWriteThere() {
        try (McpSyncClient client = clientWithToken(
                user -> user.username("jkowalski").groups(List.of(READ_GROUP, WRITE_GROUP)), PAT)) {
            McpSchema.CallToolResult result = call(client, "add_comment", Map.of("issueKey", "ABC-1", "body", "x"));

            assertThat(result.isError()).isTrue();
            assertThat(text(result)).contains(NOT_FOUND_OR_DENIED);
        }
        assertThat(BACKEND.writes()).isEmpty();
    }

    @Test
    void aReaderWithoutTheWriteGroupGetsAccessDeniedOnAnUpdateToolAndNothingReachesJira() {
        try (McpSyncClient client = clientWithToken(ServerTestSupport::reader, PAT)) {
            McpSchema.CallToolResult result = call(client, "add_comment", Map.of("issueKey", "ABC-1", "body", "x"));

            assertThat(result.isError()).isTrue();
            assertThat(text(result)).contains("\"error\":\"access_denied\"");
        }
        assertThat(BACKEND.requests()).isEmpty();
    }

    @Test
    void aLinkNarrowedToOneProjectHidesTheIssuesAndTheSearchOfAnother() {
        try (McpSyncClient client = clientWithToken(user -> {
            reader(user);
            user.virtualServer("abc-only", Map.of("jiraProjects", List.of("ABC")));
        }, PAT)) {
            McpSchema.CallToolResult inside = call(client, "get_issue", Map.of("issueKey", "ABC-1"));
            McpSchema.CallToolResult outside = call(client, "get_issue", Map.of("issueKey", "DEF-5"));
            McpSchema.CallToolResult searchOutside = call(client, "search_issues", Map.of("project", "DEF"));
            McpSchema.CallToolResult missing = call(client, "get_issue", Map.of("issueKey", "ABC-999"));
            McpSchema.CallToolResult searchMissing = call(client, "search_issues", Map.of("project", "NOPE"));

            assertThat(inside.isError()).isFalse();
            assertThat(text(outside)).contains(NOT_FOUND_OR_DENIED).isEqualTo(text(missing));
            assertThat(searchOutside.isError()).isTrue();
            assertThat(text(searchOutside)).as("outside the link, existing or not, the answer is the same")
                    .isEqualTo(text(searchMissing));
        }
        assertThat(BACKEND.searches()).as("a search outside the link never reaches Jira").isEmpty();
    }

    @Test
    void aSearchInsideTheLinkCarriesItInTheQueryAndReturnsOnlyIssuesInsideIt() {
        try (McpSyncClient client = clientWithToken(user -> {
            reader(user);
            user.virtualServer("abc-only", Map.of("jiraProjects", List.of("ABC")));
        }, PAT)) {
            McpSchema.CallToolResult result = call(client, "search_issues", Map.of("project", "ABC"));

            assertThat(result.isError()).isFalse();
            assertThat(text(result)).contains("ABC-1").doesNotContain("DEF-1");
        }
        assertThat(BACKEND.searches()).singleElement().satisfies(body ->
                assertThat(JSON.readTree(body).at("/jql").asString()).startsWith("project in (\"ABC\") AND project = \"ABC\""));
    }

    @Test
    void aJiraThatFailsIsNotADenial() {
        try (McpSyncClient client = clientWithCredential(PAT)) {
            McpSchema.CallToolResult result = call(client, "get_issue", Map.of("issueKey", "ABC-500"));

            assertThat(result.isError()).isTrue();
            assertThat(text(result)).doesNotContain(NOT_FOUND_OR_DENIED);
        }
    }

    private static Map<String, Object> with(Map<String, Object> template, String key) {
        Map<String, Object> arguments = new java.util.HashMap<>(template);
        arguments.put("issueKey", key);
        return arguments;
    }
}
