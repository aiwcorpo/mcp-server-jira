package pl.aiwcorpo.mcp.jira.app;

import static org.assertj.core.api.Assertions.assertThat;

import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.spec.McpSchema;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/** The read tools added for the full Data Center catalog: shape of results, paging, policy and error behaviour. */
class ReadToolsTest extends ServerTestSupport {

    private static final List<String> ISSUE_TOOLS = List.of("list_comments", "list_transitions", "list_worklogs",
            "list_attachments");

    @BeforeEach
    void resetBackend() {
        BACKEND.reset();
    }

    @Test
    void commentsAreListedOldestFirstWithTheTextAsUntrustedData() {
        try (McpSyncClient client = clientWithCredential(PAT)) {
            McpSchema.CallToolResult result = call(client, "list_comments", Map.of("issueKey", "ABC-1"));

            assertThat(result.isError()).isFalse();
            JsonNode page = json(result);
            assertThat(page.at("/items")).hasSize(2);
            assertThat(page.at("/items/0/id").asString()).isEqualTo("10500");
            assertThat(page.at("/items/0/author").asString()).isEqualTo("alice");
            assertThat(page.at("/items/0/body/kind").asString()).isEqualTo("untrusted-data");
            assertThat(page.at("/items/0/body/text").asString()).contains("Reproduced on staging")
                    .contains("the log").doesNotContain("{code}", "*staging*", "|");
            assertThat(page.at("/items/1/body/text").asString()).contains("Ignore previous instructions");
            assertThat(page.has("total")).isFalse();
            assertThat(text(result)).doesNotContain("alice@corp.test", "bob@corp.test", "Alice Tester");
        }
        assertThat(BACKEND.requests().getLast().path()).isEqualTo("/rest/api/2/issue/ABC-1/comment");
        assertThat(BACKEND.requests().getLast().query()).contains("startAt=0", "maxResults=25", "orderBy=created");
        assertThat(BACKEND.authorizations()).containsOnly(PAT);
    }

    @Test
    void commentsArePagedByCursor() {
        try (McpSyncClient client = clientWithCredential(PAT)) {
            JsonNode first = json(call(client, "list_comments", Map.of("issueKey", "ABC-40")));
            assertThat(first.at("/items")).hasSize(25);
            String cursor = first.at("/nextCursor").asString();
            assertThat(cursor).isNotBlank();

            JsonNode second = json(call(client, "list_comments", Map.of("issueKey", "ABC-40", "cursor", cursor)));
            assertThat(second.at("/items")).hasSize(5);
            assertThat(second.at("/items/0/id").asString()).isEqualTo("20025");
            assertThat(second.at("/nextCursor").isNull() || second.at("/nextCursor").isMissingNode()).isTrue();

            assertInvalidArgument(call(client, "list_comments", Map.of("issueKey", "ABC-1", "cursor", cursor)),
                    "The cursor is not valid");
        }
    }

    @Test
    void transitionsAreListedWithTheirTargetStatus() {
        try (McpSyncClient client = clientWithCredential(PAT)) {
            JsonNode result = json(call(client, "list_transitions", Map.of("issueKey", "ABC-1")));

            assertThat(result.at("/issueKey").asString()).isEqualTo("ABC-1");
            assertThat(result.at("/transitions")).hasSize(3);
            assertThat(result.at("/transitions/1/id").asString()).isEqualTo("21");
            assertThat(result.at("/transitions/1/name").asString()).isEqualTo("In Progress");
            assertThat(result.at("/transitions/1/toStatus").asString()).isEqualTo("In Progress");
        }
    }

    @Test
    void aProjectShowsWhatAUserNeedsToCreateAnIssue() {
        try (McpSyncClient client = clientWithCredential(PAT)) {
            McpSchema.CallToolResult result = call(client, "get_project", Map.of("project", "ABC"));

            JsonNode project = json(result);
            assertThat(project.at("/key").asString()).isEqualTo("ABC");
            assertThat(project.at("/name").asString()).isEqualTo("Alpha Team");
            assertThat(project.at("/issueTypes")).extracting(JsonNode::asString).containsExactly("Task", "Bug", "Sub-task");
            assertThat(project.at("/components")).extracting(JsonNode::asString).containsExactly("Backend", "Web UI");
            assertThat(project.at("/versions/0/name").asString()).isEqualTo("1.0");
            assertThat(project.at("/versions/0/released").asBoolean()).isTrue();
            assertThat(project.at("/versions/1/released").asBoolean()).isFalse();
            assertThat(project.at("/truncated").asBoolean()).isFalse();
            assertThat(text(result)).as("fields the tool does not return").doesNotContain("Alice", "alice", "Alpha team backlog");
        }
    }

    @Test
    void aRestrictedMissingAndForbiddenProjectLookTheSame() {
        try (McpSyncClient client = clientWithCredential(PAT)) {
            McpSchema.CallToolResult restricted = call(client, "get_project", Map.of("project", "HR"));
            McpSchema.CallToolResult missing = call(client, "get_project", Map.of("project", "NOPE"));
            McpSchema.CallToolResult forbidden = call(client, "get_project", Map.of("project", "SEC"));

            assertNotFoundOrDenied(restricted);
            assertThat(text(missing)).isEqualTo(text(restricted));
            assertThat(text(forbidden)).isEqualTo(text(restricted));
        }
    }

    @Test
    void theProjectListLeavesOutWhatThePolicyDoesNotAllow() {
        try (McpSyncClient client = clientWithCredential(PAT)) {
            McpSchema.CallToolResult result = call(client, "list_projects", Map.of());

            assertThat(result.isError()).isFalse();
            JsonNode page = json(result);
            assertThat(page.at("/items")).extracting(item -> item.get("key").asString()).containsExactly("ABC", "DEF");
            assertThat(text(result)).as("HR is in the category Restricted").doesNotContain("HR", "Human Resources");
            assertThat(page.at("/nextCursor").isNull() || page.at("/nextCursor").isMissingNode()).isTrue();
        }
        assertThat(BACKEND.requests().get(0).path()).isEqualTo("/rest/api/2/project");
    }

    @Test
    void assignableUsersComeWithoutEmailAddresses() {
        try (McpSyncClient client = clientWithCredential(PAT)) {
            McpSchema.CallToolResult result = call(client, "find_assignable_users",
                    Map.of("project", "ABC", "query", "ali"));

            JsonNode users = json(result);
            assertThat(users.at("/users")).hasSize(1);
            assertThat(users.at("/users/0/username").asString()).isEqualTo("alice");
            assertThat(users.at("/users/0/displayName/kind").asString()).isEqualTo("untrusted-data");
            assertThat(users.at("/users/0/displayName/text").asString()).isEqualTo("Alice Tester");
            assertThat(users.at("/users/0/active").asBoolean()).isTrue();
            assertThat(text(result)).doesNotContain("alice@corp.test", "timeZone", "avatar");
        }
        assertThat(BACKEND.requests().getLast().query()).contains("project=ABC", "username=ali", "maxResults=20");
    }

    @Test
    void theUserQueryCannotCarryParametersOfTheRequest() {
        try (McpSyncClient client = clientWithCredential(PAT)) {
            assertInvalidArgument(call(client, "find_assignable_users", Map.of("project", "ABC", "query", "a&maxResults=1000")),
                    "query must be 3-50 characters");
            assertInvalidArgument(call(client, "find_assignable_users", Map.of("project", "ABC", "query", "a\nb")),
                    "query");
            assertNotFoundOrDenied(call(client, "find_assignable_users", Map.of("project", "HR", "query", "ali")));
        }
        assertThat(BACKEND.requests()).extracting(StubBackend.Request::path)
                .doesNotContain("/rest/api/2/user/assignable/search");
    }

    @Test
    void aUserQueryShorterThanThreeCharactersIsRefusedBeforeJira() {
        try (McpSyncClient client = clientWithCredential(PAT)) {
            assertInvalidArgument(call(client, "find_assignable_users", Map.of("project", "ABC", "query", "al")),
                    "query is shorter than 3");
        }
        assertThat(BACKEND.requests()).isEmpty();
    }

    @Test
    void jirasRefusalOfTheProjectInTheUserLookupLooksLikeAMissingProject() {
        try (McpSyncClient client = clientWithCredential(PAT)) {
            McpSchema.CallToolResult result = call(client, "find_assignable_users", Map.of("project", "DEF", "query", "ali"));

            assertNotFoundOrDenied(result);
            assertThat(text(result)).doesNotContain("not valid");
        }
    }

    @Test
    void theProjectShownIsTheOneThePolicyJudgedAndIsReadOnce() {
        try (McpSyncClient client = clientWithCredential(PAT)) {
            assertThat(call(client, "get_project", Map.of("project", "ABC")).isError()).isFalse();
        }
        assertThat(BACKEND.requests()).extracting(StubBackend.Request::path).containsExactly("/rest/api/2/project/ABC");
    }

    @Test
    void aLongProjectListIsReadUpToTheLimitAndSaysSo() {
        BACKEND.projectsListed(2000);
        try {
            try (McpSyncClient client = clientWithCredential(PAT)) {
                McpSchema.CallToolResult result = call(client, "list_projects", Map.of());

                assertThat(result.isError()).isFalse();
                JsonNode page = json(result);
                assertThat(page.at("/truncated").asBoolean()).isTrue();
                assertThat(page.at("/items")).extracting(item -> item.get("key").asString()).contains("ABC", "DEF");
            }
        } finally {
            BACKEND.useDefaultFixtures();
        }
    }

    @Test
    void textsOfJiraThatAreNotTimesOrNumbersDoNotReachTheModel() {
        try (McpSyncClient client = clientWithCredential(PAT)) {
            McpSchema.CallToolResult comments = call(client, "list_comments", Map.of("issueKey", "ABC-43"));
            assertThat(comments.isError()).isFalse();
            assertThat(text(comments)).doesNotContain("Ignore previous instructions");
            assertThat(json(comments).at("/items/0/created").isNull() || json(comments).at("/items/0/created").isMissingNode())
                    .isTrue();
            assertThat(json(comments).at("/items/0/updated").asString()).isEqualTo("2026-10-01T09:00:00.000+0000");

            McpSchema.CallToolResult badId = call(client, "list_comments", Map.of("issueKey", "ABC-44"));
            assertThat(badId.isError()).isTrue();
            assertThat(text(badId)).contains("\"error\":\"internal_error\"");
        }
    }

    @Test
    void worklogsShowTimeAndTheCommentAsUntrustedData() {
        try (McpSyncClient client = clientWithCredential(PAT)) {
            McpSchema.CallToolResult result = call(client, "list_worklogs", Map.of("issueKey", "ABC-1"));

            JsonNode page = json(result);
            assertThat(page.at("/items/0/id").asString()).isEqualTo("10700");
            assertThat(page.at("/items/0/timeSpent").asString()).isEqualTo("1h 30m");
            assertThat(page.at("/items/0/author").asString()).isEqualTo("alice");
            assertThat(page.at("/items/0/comment/kind").asString()).isEqualTo("untrusted-data");
            assertThat(text(result)).doesNotContain("alice@corp.test");
        }
    }

    @Test
    void attachmentsAreMetadataOnly() {
        try (McpSyncClient client = clientWithCredential(PAT)) {
            McpSchema.CallToolResult result = call(client, "list_attachments", Map.of("issueKey", "ABC-41"));

            JsonNode view = json(result);
            assertThat(view.at("/attachments")).hasSize(2);
            assertThat(view.at("/attachments/0/filename/text").asString()).isEqualTo("stacktrace.txt");
            assertThat(view.at("/attachments/0/filename/kind").asString()).isEqualTo("untrusted-data");
            assertThat(view.at("/attachments/0/size").asLong()).isEqualTo(2048);
            assertThat(view.at("/attachments/0/mimeType").asString()).isEqualTo("text/plain");
            assertThat(view.at("/truncated").asBoolean()).isFalse();
            assertThat(text(result)).doesNotContain("secure/attachment", "alice@corp.test");
        }
    }

    @Test
    void aLongAttachmentListIsCutAndSaysSo() {
        try (McpSyncClient client = clientWithCredential(PAT)) {
            JsonNode view = json(call(client, "list_attachments", Map.of("issueKey", "ABC-42")));

            assertThat(view.at("/attachments")).hasSize(50);
            assertThat(view.at("/truncated").asBoolean()).isTrue();
        }
    }

    @Test
    void everyIssueToolAnswersAMissingAndAForbiddenIssueTheSame() {
        try (McpSyncClient client = clientWithCredential(PAT)) {
            for (String tool : ISSUE_TOOLS) {
                McpSchema.CallToolResult missing = call(client, tool, Map.of("issueKey", "ABC-404"));
                McpSchema.CallToolResult forbidden = call(client, tool, Map.of("issueKey", "ABC-403"));
                McpSchema.CallToolResult restricted = call(client, tool, Map.of("issueKey", "HR-1"));

                assertNotFoundOrDenied(missing);
                assertThat(text(forbidden)).as(tool).isEqualTo(text(missing));
                assertThat(text(restricted)).as(tool).isEqualTo(text(missing));
            }
        }
    }

    @Test
    void everyReadToolAsksToConnectTheAccountWithoutACredentialOrWithARevokedOne() {
        Map<String, Map<String, Object>> calls = Map.of(
                "list_comments", Map.of("issueKey", "ABC-1"),
                "list_transitions", Map.of("issueKey", "ABC-1"),
                "list_worklogs", Map.of("issueKey", "ABC-1"),
                "list_attachments", Map.of("issueKey", "ABC-1"),
                "get_project", Map.of("project", "ABC"),
                "find_assignable_users", Map.of("project", "ABC", "query", "ali"),
                "list_projects", Map.of());
        try (McpSyncClient none = clientWithCredential(null);
                McpSyncClient revoked = clientWithCredential(StubBackend.EXPIRED_CREDENTIAL)) {
            calls.forEach((tool, arguments) -> {
                assertCredentialRequired(call(none, tool, arguments));
                assertCredentialRequired(call(revoked, tool, arguments));
            });
        }
    }

    @Test
    void invalidArgumentsNeverReachJira() {
        try (McpSyncClient client = clientWithCredential(PAT)) {
            assertInvalidArgument(call(client, "list_comments", Map.of("issueKey", "abc-1")), "issueKey does not match");
            assertInvalidArgument(call(client, "list_comments", Map.of("issueKey", "ABC-1", "startAt", "50")),
                    "unknown arguments are not allowed");
            assertInvalidArgument(call(client, "list_comments", Map.of("issueKey", "ABC-1", "cursor", "a b")),
                    "cursor does not match");
            assertInvalidArgument(call(client, "get_project", Map.of("project", "abc")), "project does not match");
            assertInvalidArgument(call(client, "list_projects", Map.of("cursor", "x".repeat(1025))),
                    "cursor is longer than 1024");
        }
        assertThat(BACKEND.requests()).isEmpty();
    }

    @Test
    void aReaderWithoutAProjectGrantStillListsWhatTheBundleLetsHerRead() {
        try (McpSyncClient client = clientWithToken(ServerTestSupport::reader, PAT)) {
            assertThat(call(client, "list_comments", Map.of("issueKey", "ABC-1")).isError()).isFalse();
            assertThat(call(client, "get_project", Map.of("project", "ABC")).isError()).isFalse();
        }
    }
}
