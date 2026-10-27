package pl.aiwcorpo.mcp.jira.app;

import static org.assertj.core.api.Assertions.assertThat;

import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.spec.McpSchema;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/** {@code search_issues}: the server writes the JQL from bounded fields and keeps the search in one project. */
class SearchIssuesToolTest extends ServerTestSupport {

    @BeforeEach
    void resetBackend() {
        BACKEND.reset();
    }

    @Test
    void searchesOneProjectWithJqlBuiltByTheServer() {
        try (McpSyncClient client = clientWithCredential(PAT)) {
            McpSchema.CallToolResult result = call(client, "search_issues", Map.of("project", "ABC",
                    "jql_filter", Map.of("status", "In Progress", "assignee", "alice", "type", "Bug"),
                    "text", "login* (page)"));

            assertThat(result.isError()).isFalse();
            JsonNode found = json(result);
            assertThat(found.at("/project").asString()).isEqualTo("ABC");
            assertThat(found.at("/returned").asInt()).isEqualTo(2);
            assertThat(found.at("/issues/0/key").asString()).isEqualTo("ABC-1");
            assertThat(found.at("/issues/0/summary/text").asString()).isEqualTo("Login page returns 500");
            assertThat(found.at("/issues/0/summary/kind").asString()).isEqualTo("untrusted-data");
            assertThat(found.at("/total").asInt()).as("the dropped hit is not counted").isEqualTo(2);
            assertThat(found.at("/issues/1/assignee").asString()).isEqualTo("alice");
            assertThat(text(result)).as("an issue of another project is dropped").doesNotContain("DEF-1")
                    .doesNotContain("alice@corp.test", "Alice Tester");
        }
        assertThat(BACKEND.requests()).extracting(StubBackend.Request::path)
                .containsExactly("/rest/api/2/project/ABC", "/rest/api/2/search");
        JsonNode search = JSON.readTree(BACKEND.requests().get(1).body());
        assertThat(search.at("/jql").asString()).isEqualTo("project = \"ABC\" AND status = \"In Progress\""
                + " AND assignee = \"alice\" AND issuetype = \"Bug\" AND text ~ \"login page\" ORDER BY updated DESC");
        assertThat(search.at("/maxResults").asInt()).isEqualTo(25);
        assertThat(BACKEND.authorizations()).containsOnly(PAT);
    }

    @Test
    void onlyTheProjectIsRequired() {
        try (McpSyncClient client = clientWithCredential(PAT)) {
            assertThat(call(client, "search_issues", Map.of("project", "ABC")).isError()).isFalse();
        }
        assertThat(JSON.readTree(BACKEND.requests().get(1).body()).at("/jql").asString())
                .isEqualTo("project = \"ABC\" ORDER BY updated DESC");
    }

    @Test
    void aMissingAndAForbiddenProjectLookTheSameAndAreNotSearched() {
        try (McpSyncClient client = clientWithCredential(PAT)) {
            McpSchema.CallToolResult missing = call(client, "search_issues", Map.of("project", "NOPE"));
            McpSchema.CallToolResult forbidden = call(client, "search_issues", Map.of("project", "SEC"));

            assertNotFoundOrDenied(missing);
            assertThat(text(forbidden)).isEqualTo(text(missing));
        }
        assertThat(BACKEND.requests()).extracting(StubBackend.Request::path).doesNotContain("/rest/api/2/search");
    }

    @Test
    void withoutCredentialAsksToConnectTheAccount() {
        try (McpSyncClient client = clientWithCredential(null)) {
            assertCredentialRequired(call(client, "search_issues", Map.of("project", "ABC")));
        }
        assertThat(BACKEND.requests()).isEmpty();
    }

    @Test
    void aPatRejectedByJiraAsksToReconnect() {
        try (McpSyncClient client = clientWithCredential(StubBackend.EXPIRED_CREDENTIAL)) {
            assertCredentialRequired(call(client, "search_issues", Map.of("project", "ABC")));
        }
    }

    @Test
    void anInvalidProjectKeyIsRejectedBeforeJira() {
        try (McpSyncClient client = clientWithCredential(PAT)) {
            for (String project : new String[] {"abc", "1AB", "AB-C", "ABC OR", "AB\"C"}) {
                assertInvalidArgument(call(client, "search_issues", Map.of("project", project)), "project does not match the required format");
            }
        }
        assertThat(BACKEND.requests()).isEmpty();
    }

    @Test
    void filterValuesCannotCarryJql() {
        try (McpSyncClient client = clientWithCredential(PAT)) {
            assertInvalidArgument(call(client, "search_issues", Map.of("project", "ABC",
                    "jql_filter", Map.of("status", "x\" OR project = \"HR"))), "jql_filter.status must be");
            assertInvalidArgument(call(client, "search_issues", Map.of("project", "ABC",
                    "jql_filter", Map.of("assignee", "alice OR 1=1"))), "jql_filter.assignee must be");
            assertInvalidArgument(call(client, "search_issues", Map.of("project", "ABC",
                    "jql_filter", Map.of("type", "Bug\" ORDER BY"))), "jql_filter.type must be");
            assertInvalidArgument(call(client, "search_issues", Map.of("project", "ABC", "text", "*?~")),
                    "text must be");
            assertInvalidArgument(call(client, "search_issues", Map.of("project", "ABC", "text", "a\nb")),
                    "text must be");
            assertInvalidArgument(call(client, "search_issues", Map.of("project", "ABC", "text", "AND OR NOT")),
                    "text must be");
            assertInvalidArgument(call(client, "search_issues", Map.of("project", "A")), "project is shorter than 2");
            assertInvalidArgument(call(client, "search_issues", Map.of("project", "ABC", "jql", "project = HR")),
                    "unknown arguments are not allowed");
            assertInvalidArgument(call(client, "search_issues", Map.of("project", "ABC",
                    "jql_filter", Map.of("reporter", "alice"))), "unknown arguments are not allowed");
            assertInvalidArgument(call(client, "search_issues", Map.of("project", "ABC",
                    "jql_filter", Map.of("status", "x".repeat(61)))), "jql_filter");
            assertInvalidArgument(call(client, "search_issues", Map.of("project", "ABC", "text", "x".repeat(201))),
                    "text is longer than 200 characters");
        }
        assertThat(BACKEND.requests()).isEmpty();
    }

    @Test
    void aValueJiraDoesNotKnowIsAnInvalidArgumentWithoutJirasText() {
        try (McpSyncClient client = clientWithCredential(PAT)) {
            McpSchema.CallToolResult result = call(client, "search_issues", Map.of("project", "ABC",
                    "jql_filter", Map.of("status", "Nope")));

            assertInvalidArgument(result, "Jira does not know one of the values");
            assertThat(text(result)).doesNotContain("Nope");
        }
    }

    @Test
    void booleanOperatorsInTheTextAreNotPassedToJira() {
        try (McpSyncClient client = clientWithCredential(PAT)) {
            assertThat(call(client, "search_issues", Map.of("project", "ABC", "text", "login AND page OR NOT")).isError())
                    .isFalse();
        }
        assertThat(JSON.readTree(BACKEND.requests().get(1).body()).at("/jql").asString())
                .contains("text ~ \"login page\"");
    }
}
