package pl.aiwcorpo.mcp.jira.app;

import static org.assertj.core.api.Assertions.assertThat;

import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.spec.McpSchema;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/** Watchers and the removal of an issue link: what reaches Jira, what is refused first, how Jira's refusals look. */
class CatalogWriteToolsTest extends ServerTestSupport {

    private static final Map<String, Map<String, Object>> WRITES = Map.of(
            "add_watcher", Map.of("issueKey", "ABC-1", "username", "alice"),
            "unwatch_issue", Map.of("issueKey", "ABC-1"),
            "detach_issue_link", Map.of("outwardIssueKey", "ABC-1", "inwardIssueKey", "ABC-2", "linkId", "90001"));

    @BeforeEach
    void resetBackend() {
        BACKEND.reset();
    }

    private static StubBackend.Request lastWrite() {
        return BACKEND.writes().getLast();
    }

    @Test
    void withoutAUsernameTheCallingUserBecomesAWatcher() {
        try (McpSyncClient client = clientWithCredential(PAT)) {
            McpSchema.CallToolResult result = call(client, "add_watcher", Map.of("issueKey", "ABC-1"));

            assertThat(result.isError()).isFalse();
            assertThat(json(result).at("/issueKey").asString()).isEqualTo("ABC-1");
            assertThat(json(result).at("/username").asString()).isEqualTo("jkowalski");
        }
        assertThat(BACKEND.requests()).extracting(StubBackend.Request::path)
                .containsExactly("/rest/api/2/issue/ABC-1", "/rest/api/2/project/ABC", "/rest/api/2/myself",
                        "/rest/api/2/issue/ABC-1/watchers");
        assertThat(lastWrite().method()).isEqualTo("POST");
        assertThat(lastWrite().authorization()).isEqualTo(PAT);
        assertThat(lastWrite().body()).as("Jira takes the bare JSON string").isEqualTo("\"jkowalski\"");
    }

    @Test
    void aNamedUserIsAddedAndMyselfIsNotAsked() {
        try (McpSyncClient client = clientWithCredential(PAT)) {
            JsonNode result = json(call(client, "add_watcher", Map.of("issueKey", "ABC-3", "username", "alice")));

            assertThat(result.at("/issueKey").asString()).as("ABC-3 was moved").isEqualTo("DEF-5");
            assertThat(result.at("/username").asString()).isEqualTo("alice");
        }
        assertThat(lastWrite().path()).isEqualTo("/rest/api/2/issue/DEF-5/watchers");
        assertThat(lastWrite().body()).isEqualTo("\"alice\"");
        assertThat(BACKEND.requests()).extracting(StubBackend.Request::path).doesNotContain("/rest/api/2/myself");
    }

    @Test
    void aUserJiraDoesNotKnowIsAnInvalidArgumentWithoutJirasText() {
        try (McpSyncClient client = clientWithCredential(PAT)) {
            McpSchema.CallToolResult result = call(client, "add_watcher", Map.of("issueKey", "ABC-1", "username", "ghost"));

            assertInvalidArgument(result, "Jira refused the change of the watcher list");
            assertThat(text(result)).doesNotContain("does not exist");
        }
    }

    @Test
    void aBadUsernameNeverReachesJira() {
        try (McpSyncClient client = clientWithCredential(PAT)) {
            assertInvalidArgument(call(client, "add_watcher", Map.of("issueKey", "ABC-1", "username", "alice OR 1=1")),
                    "username must be a username");
            assertInvalidArgument(call(client, "add_watcher", Map.of("issueKey", "ABC-1", "username", "a\"b")),
                    "username must be a username");
        }
        assertThat(BACKEND.writes()).isEmpty();
    }

    @Test
    void theCallingUserStopsWatchingAndNoOtherUserCanBeNamed() {
        try (McpSyncClient client = clientWithCredential(PAT)) {
            McpSchema.CallToolResult result = call(client, "unwatch_issue", Map.of("issueKey", "ABC-1"));

            assertThat(result.isError()).isFalse();
            assertThat(json(result).at("/issueKey").asString()).isEqualTo("ABC-1");
            assertThat(json(result).at("/username").asString()).isEqualTo("jkowalski");
            int requests = BACKEND.requests().size();
            assertInvalidArgument(call(client, "unwatch_issue", Map.of("issueKey", "ABC-1", "username", "alice")),
                    "unknown arguments are not allowed");
            assertThat(BACKEND.requests()).hasSize(requests);
        }
        assertThat(BACKEND.writes()).singleElement().satisfies(request -> {
            assertThat(request.method()).isEqualTo("DELETE");
            assertThat(request.path()).isEqualTo("/rest/api/2/issue/ABC-1/watchers");
            assertThat(request.query()).isEqualTo("username=jkowalski");
            assertThat(request.authorization()).isEqualTo(PAT);
        });
    }

    @Test
    void aLinkIsRemovedWhenItJoinsTheTwoNamedIssuesInEitherOrder() {
        try (McpSyncClient client = clientWithCredential(PAT)) {
            McpSchema.CallToolResult result = call(client, "detach_issue_link",
                    Map.of("outwardIssueKey", "ABC-1", "inwardIssueKey", "ABC-2", "linkId", "90001"));
            assertThat(result.isError()).isFalse();
            assertThat(json(result).at("/outwardIssueKey").asString()).isEqualTo("ABC-1");
            assertThat(json(result).at("/inwardIssueKey").asString()).isEqualTo("ABC-2");
            assertThat(json(result).at("/linkId").asString()).isEqualTo("90001");

            assertThat(call(client, "detach_issue_link",
                    Map.of("outwardIssueKey", "ABC-2", "inwardIssueKey", "ABC-1", "linkId", "90001")).isError()).isFalse();
        }
        assertThat(BACKEND.writes()).hasSize(2).allSatisfy(request -> {
            assertThat(request.method()).isEqualTo("DELETE");
            assertThat(request.path()).isEqualTo("/rest/api/2/issueLink/90001");
            assertThat(request.authorization()).isEqualTo(PAT);
        });
    }

    @Test
    void aLinkOfOtherIssuesIsNotRemovedThroughTwoIssuesTheCallerNamed() {
        try (McpSyncClient client = clientWithCredential(PAT)) {
            McpSchema.CallToolResult otherEnd = call(client, "detach_issue_link",
                    Map.of("outwardIssueKey", "ABC-1", "inwardIssueKey", "ABC-2", "linkId", "90002"));
            McpSchema.CallToolResult otherPair = call(client, "detach_issue_link",
                    Map.of("outwardIssueKey", "ABC-1", "inwardIssueKey", "DEF-5", "linkId", "90001"));
            McpSchema.CallToolResult unknown = call(client, "detach_issue_link",
                    Map.of("outwardIssueKey", "ABC-1", "inwardIssueKey", "ABC-2", "linkId", "99999"));

            assertNotFoundOrDenied(otherEnd);
            assertThat(text(otherPair)).isEqualTo(text(otherEnd));
            assertThat(text(unknown)).isEqualTo(text(otherEnd));
        }
        assertThat(BACKEND.writes()).isEmpty();
    }

    @Test
    void bothIssuesAreJudgedAndTheRestrictedEndOfALinkCannotBeNamed() {
        try (McpSyncClient client = clientWithCredential(PAT)) {
            McpSchema.CallToolResult restricted = call(client, "detach_issue_link",
                    Map.of("outwardIssueKey", "ABC-1", "inwardIssueKey", "HR-1", "linkId", "90002"));
            McpSchema.CallToolResult missing = call(client, "detach_issue_link",
                    Map.of("outwardIssueKey", "ABC-1", "inwardIssueKey", "ABC-404", "linkId", "90002"));

            assertNotFoundOrDenied(restricted);
            assertThat(text(missing)).isEqualTo(text(restricted));
            assertInvalidArgument(call(client, "detach_issue_link",
                    Map.of("outwardIssueKey", "ABC-1", "inwardIssueKey", "ABC-1", "linkId", "90001")), "cannot be linked to itself");
        }
        assertThat(BACKEND.requests()).extracting(StubBackend.Request::path).noneMatch(path -> path.contains("/issueLink/"));
    }

    @Test
    void jirasRefusalAndAnUnconfirmedDeletionAreNotReportedAsSuccess() {
        try (McpSyncClient client = clientWithCredential(PAT)) {
            McpSchema.CallToolResult refused = call(client, "detach_issue_link",
                    Map.of("outwardIssueKey", "ABC-1", "inwardIssueKey", "ABC-2", "linkId", "90003"));
            McpSchema.CallToolResult unconfirmed = call(client, "detach_issue_link",
                    Map.of("outwardIssueKey", "ABC-1", "inwardIssueKey", "ABC-2", "linkId", "90004"));

            assertInvalidArgument(refused, "Jira refused to remove the link");
            assertThat(text(refused)).doesNotContain("cannot be removed");
            assertThat(unconfirmed.isError()).isTrue();
            assertThat(text(unconfirmed)).contains("\"error\":\"internal_error\"");
        }
    }

    @Test
    void theLinkIdIsADecimalNumber() {
        try (McpSyncClient client = clientWithCredential(PAT)) {
            assertInvalidArgument(call(client, "detach_issue_link",
                    Map.of("outwardIssueKey", "ABC-1", "inwardIssueKey", "ABC-2", "linkId", "../90001")), "linkId does not match");
            assertInvalidArgument(call(client, "detach_issue_link",
                    Map.of("outwardIssueKey", "ABC-1", "inwardIssueKey", "ABC-2", "linkId", "")), "linkId");
        }
        assertThat(BACKEND.requests()).isEmpty();
    }

    @Test
    void everyWatcherToolOnAnInvisibleIssueAnswersLikeOnAMissingOne() {
        try (McpSyncClient client = clientWithCredential(PAT)) {
            for (String tool : List.of("add_watcher", "unwatch_issue")) {
                Map<String, Object> missing = new java.util.HashMap<>(WRITES.get(tool));
                missing.put("issueKey", "ABC-404");
                Map<String, Object> forbidden = new java.util.HashMap<>(missing);
                forbidden.put("issueKey", "ABC-403");
                Map<String, Object> restricted = new java.util.HashMap<>(missing);
                restricted.put("issueKey", "HR-1");

                McpSchema.CallToolResult first = call(client, tool, missing);
                assertNotFoundOrDenied(first);
                assertThat(text(call(client, tool, forbidden))).as(tool).isEqualTo(text(first));
                assertThat(text(call(client, tool, restricted))).as(tool).isEqualTo(text(first));
            }
        }
        assertThat(BACKEND.writes()).isEmpty();
    }

    @Test
    void everyNewWriteToolIsRefusedWithoutTheWriteGroupAndNothingReachesJira() {
        try (McpSyncClient reader = clientWithToken(ServerTestSupport::reader, PAT)) {
            WRITES.forEach((tool, arguments) -> {
                McpSchema.CallToolResult result = call(reader, tool, arguments);
                assertThat(result.isError()).as(tool).isTrue();
                assertThat(text(result)).as(tool).contains("\"error\":\"access_denied\"");
            });
        }
        assertThat(BACKEND.requests()).isEmpty();
    }

    @Test
    void aWriteGroupWithoutTheProjectScopeChangesNothing() {
        try (McpSyncClient client = clientWithToken(
                user -> user.username("jkowalski").groups(List.of(READ_GROUP, WRITE_GROUP)), PAT)) {
            WRITES.forEach((tool, arguments) -> assertNotFoundOrDenied(call(client, tool, arguments)));
        }
        assertThat(BACKEND.requests()).as("the grant is judged before the resolver asks Jira").isEmpty();
    }

    @Test
    void everyNewWriteToolAsksToConnectTheAccountWithoutACredentialOrWithARevokedOne() {
        try (McpSyncClient none = clientWithCredential(null);
                McpSyncClient revoked = clientWithCredential(StubBackend.EXPIRED_CREDENTIAL)) {
            WRITES.forEach((tool, arguments) -> {
                assertCredentialRequired(call(none, tool, arguments));
                assertCredentialRequired(call(revoked, tool, arguments));
            });
        }
        assertThat(BACKEND.writes()).isEmpty();
    }
}
