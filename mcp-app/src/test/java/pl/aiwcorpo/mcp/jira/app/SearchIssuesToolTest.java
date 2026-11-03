package pl.aiwcorpo.mcp.jira.app;

import static org.assertj.core.api.Assertions.assertThat;

import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.spec.McpSchema;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/**
 * {@code search_issues}: the server writes the JQL from the fields the contracts allow (status, assignee, text), and
 * names the project as the scope of the query, and applies the issue type to the rows, because it is not a field the query may use.
 */
class SearchIssuesToolTest extends ServerTestSupport {

    @BeforeEach
    void resetBackend() {
        BACKEND.reset();
    }

    @Test
    void searchesOneProjectWithJqlBuiltByTheServer() {
        try (McpSyncClient client = clientWithCredential(PAT)) {
            McpSchema.CallToolResult result = call(client, "search_issues", Map.of("project", "ABC",
                    "jql_filter", Map.of("status", "In Progress", "assignee", "alice"),
                    "text", "login* (page)"));

            assertThat(result.isError()).isFalse();
            JsonNode found = json(result);
            assertThat(found.at("/items/0/key").asString()).isEqualTo("ABC-1");
            assertThat(found.at("/items/0/summary/text").asString()).isEqualTo("Login page returns 500");
            assertThat(found.at("/items/0/summary/kind").asString()).isEqualTo("untrusted-data");
            assertThat(found.has("total")).as("Jira's total counts what the filters remove").isFalse();
            assertThat(found.has("hasMore")).as("replaced by nextCursor").isFalse();
            assertThat(found.at("/nextCursor").isNull() || found.at("/nextCursor").isMissingNode()).isTrue();
            assertThat(found.at("/items")).hasSize(2);
            assertThat(found.at("/items/1/assignee").asString()).isEqualTo("alice");
            assertThat(text(result)).as("an issue of another project is dropped").doesNotContain("DEF-1")
                    .doesNotContain("alice@corp.test", "Alice Tester");
        }
        assertThat(BACKEND.requests()).extracting(StubBackend.Request::path).first().isEqualTo("/rest/api/2/project/ABC");
        assertThat(BACKEND.searches()).hasSize(1);
        JsonNode search = JSON.readTree(BACKEND.searches().get(0));
        assertThat(search.at("/jql").asString()).isEqualTo("(status = \"In Progress\" AND assignee = \"alice\""
                + " AND text ~ \"login page\") AND project in (\"ABC\") ORDER BY updated DESC");
        assertThat(search.at("/jql").asString()).as("the project is the scope clause, the type is not in the query")
                .doesNotContain("issuetype");
        assertThat(search.at("/maxResults").asInt()).isEqualTo(25);
        assertThat(search.at("/startAt").asInt()).isZero();
        assertThat(BACKEND.authorizations()).containsOnly(PAT);
    }

    @Test
    void theIssueTypeNarrowsTheRowsAndNotTheQuery() {
        try (McpSyncClient client = clientWithCredential(PAT)) {
            McpSchema.CallToolResult result = call(client, "search_issues", Map.of("project", "ABC",
                    "jql_filter", Map.of("type", "bug")));

            assertThat(result.isError()).isFalse();
            assertThat(json(result).at("/items")).hasSize(1);
            assertThat(json(result).at("/items/0/key").asString()).isEqualTo("ABC-2");
        }
        assertThat(JSON.readTree(BACKEND.searches().get(0)).at("/jql").asString())
                .isEqualTo("project in (\"ABC\") ORDER BY updated DESC");
    }

    @Test
    void onlyTheProjectIsRequired() {
        try (McpSyncClient client = clientWithCredential(PAT)) {
            assertThat(call(client, "search_issues", Map.of("project", "ABC")).isError()).isFalse();
        }
        assertThat(JSON.readTree(BACKEND.searches().get(0)).at("/jql").asString())
                .isEqualTo("project in (\"ABC\") ORDER BY updated DESC");
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
                    "jql_filter", Map.of("status", "x".repeat(61)))), "jql_filter.status is longer than 60 characters");
            assertInvalidArgument(call(client, "search_issues", Map.of("project", "ABC", "text", "x".repeat(201))),
                    "text is longer than 200 characters");
        }
        assertThat(BACKEND.searches()).as("a value that fails the checks of the tool never becomes a query").isEmpty();
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
        assertThat(JSON.readTree(BACKEND.searches().get(0)).at("/jql").asString())
                .isEqualTo("(text ~ \"login page\") AND project in (\"ABC\") ORDER BY updated DESC");
    }

    @Test
    void aLongResultIsPagedWithACursorAndNoTotal() {
        try (McpSyncClient client = clientWithCredential(PAT)) {
            JsonNode first = json(call(client, "search_issues", Map.of("project", "ABC", "text", "many hits")));
            assertThat(first.at("/items")).hasSize(25);
            assertThat(first.at("/items/0/key").asString()).isEqualTo("ABC-100");
            String cursor = first.at("/nextCursor").asString();
            assertThat(cursor).matches("^[A-Za-z0-9_.-]{1,1024}$");
            assertThat(first.has("total")).isFalse();

            JsonNode second = json(call(client, "search_issues",
                    Map.of("project", "ABC", "text", "many hits", "cursor", cursor)));
            assertThat(second.at("/items/0/key").asString()).isEqualTo("ABC-125");
            assertThat(second.at("/items")).hasSize(25);

            JsonNode third = json(call(client, "search_issues",
                    Map.of("project", "ABC", "text", "many hits", "cursor", second.at("/nextCursor").asString())));
            assertThat(third.at("/items")).hasSize(10);
            assertThat(third.at("/nextCursor").isNull() || third.at("/nextCursor").isMissingNode())
                    .as("the last page has no cursor").isTrue();
        }
        assertThat(BACKEND.searches()).extracting(b -> JSON.readTree(b).at("/startAt").asInt()).containsExactly(0, 25, 50);
    }

    @Test
    void aHiddenHitLeavesNoTraceInThePage() {
        try (McpSyncClient client = clientWithCredential(PAT)) {
            JsonNode page = json(call(client, "search_issues", Map.of("project", "ABC", "text", "many hits one hidden")));

            assertThat(page.at("/items")).as("ABC-30 is in a restricted project and is removed; the short first page is "
                    + "filled from the next one so its size does not show it").hasSize(49);
            assertThat(page.toString()).doesNotContain("ABC-30");
            assertThat(page.at("/nextCursor").asString()).isNotBlank();
            assertThat(page.has("hasMore") || page.has("omitted") && page.at("/omitted").size() > 0).isFalse();
        }
    }

    @Test
    void theNextPageStartsWhereTheFilledOneEnded() {
        try (McpSyncClient client = clientWithCredential(PAT)) {
            String cursor = json(call(client, "search_issues", Map.of("project", "ABC", "text", "many hits one hidden")))
                    .at("/nextCursor").asString();
            JsonNode next = json(call(client, "search_issues",
                    Map.of("project", "ABC", "text", "many hits one hidden", "cursor", cursor)));

            assertThat(next.at("/items/0/key").asString()).isEqualTo("ABC-149");
        }
        assertThat(BACKEND.searches()).extracting(b -> JSON.readTree(b).at("/startAt").asInt()).containsExactly(0, 25, 50);
    }

    @Test
    void aCursorOnlyWorksForTheQueryAndTheUserItWasIssuedFor() {
        String cursor;
        try (McpSyncClient client = clientWithCredential(PAT)) {
            cursor = json(call(client, "search_issues", Map.of("project", "ABC", "text", "many hits")))
                    .at("/nextCursor").asString();
            BACKEND.reset();

            assertInvalidArgument(call(client, "search_issues",
                    Map.of("project", "ABC", "text", "many hits", "jql_filter", Map.of("status", "To Do"), "cursor", cursor)),
                    "The cursor is not valid for this query or has expired");
            assertInvalidArgument(call(client, "search_issues",
                    Map.of("project", "ABC", "text", "many hit", "cursor", cursor)), "The cursor is not valid");
            assertInvalidArgument(call(client, "search_issues",
                    Map.of("project", "ABC", "text", "many hits", "cursor", cursor + "x")), "The cursor is not valid");
            assertInvalidArgument(call(client, "search_issues",
                    Map.of("project", "ABC", "text", "many hits", "cursor", "AAAA.AAAA")), "The cursor is not valid");
        }
        try (McpSyncClient other = clientWithToken(user -> {
            editor(user);
            user.subject("someone-else");
        }, PAT)) {
            assertInvalidArgument(call(other, "search_issues",
                    Map.of("project", "ABC", "text", "many hits", "cursor", cursor)), "The cursor is not valid");
        }
        assertThat(BACKEND.searches()).as("a refused cursor never reaches Jira").isEmpty();
    }

    @Test
    void aCursorOfAnotherToolIsRefused() {
        try (McpSyncClient client = clientWithCredential(PAT)) {
            String cursor = json(call(client, "search_issues", Map.of("project", "ABC", "text", "many hits")))
                    .at("/nextCursor").asString();

            assertInvalidArgument(call(client, "list_comments", Map.of("issueKey", "ABC-40", "cursor", cursor)),
                    "The cursor is not valid");
        }
    }

    @Test
    void aProjectAddressedByItsFormerKeyIsSearchedAndItsRowsAreKept() {
        try (McpSyncClient client = clientWithCredential(PAT)) {
            JsonNode found = json(call(client, "search_issues", Map.of("project", "OLD")));

            assertThat(found.at("/items")).extracting(item -> item.get("key").asString()).containsExactly("ABC-1", "ABC-2");
        }
        assertThat(JSON.readTree(BACKEND.searches().get(0)).at("/jql").asString())
                .as("the query names the project under the key Jira reports now").contains("project in (\"ABC\")");
    }

    @Test
    void sortAndSummaryFilterGoThroughTheStructuredQuery() {
        try (McpSyncClient client = clientWithCredential(PAT)) {
            assertThat(call(client, "search_issues", Map.of("project", "ABC", "order_by", "created", "order", "asc",
                    "jql_filter", Map.of("summary", "login (page)"))).isError()).isFalse();
        }
        assertThat(JSON.readTree(BACKEND.searches().get(0)).at("/jql").asString())
                .isEqualTo("(summary ~ \"login page\") AND project in (\"ABC\") ORDER BY created ASC");
    }

    @Test
    void sortFieldsOutsideTheClosedListAreRefusedBeforeJira() {
        try (McpSyncClient client = clientWithCredential(PAT)) {
            assertInvalidArgument(call(client, "search_issues", Map.of("project", "ABC", "order_by", "reporter")),
                    "order_by does not match the required format");
            assertInvalidArgument(call(client, "search_issues", Map.of("project", "ABC", "order_by", "updated; DROP")),
                    "order_by");
            assertInvalidArgument(call(client, "search_issues", Map.of("project", "ABC", "order", "sideways")),
                    "order does not match the required format");
        }
        assertThat(BACKEND.searches()).isEmpty();
    }
}
