package pl.aiwcorpo.mcp.jira.app;

import static org.assertj.core.api.Assertions.assertThat;

import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.spec.McpSchema;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * {@code delete_issue}: DESTRUCTIVE, the {@code delete} action of the policy on the issue the resolver describes.
 * The one-time code is the gateway's business and is not tested here.
 */
class DeleteIssueToolTest extends ServerTestSupport {

    private static final String NOT_FOUND_OR_DENIED = "\"error\":\"not_found_or_denied\"";

    @BeforeEach
    void resetBackend() {
        BACKEND.reset();
    }

    private McpSyncClient eraserClient(String credential) {
        return clientWithToken(ServerTestSupport::eraser, credential);
    }

    @Test
    void deletesTheIssueAsTheUser() {
        try (McpSyncClient client = eraserClient(PAT)) {
            McpSchema.CallToolResult result = call(client, "delete_issue", Map.of("issueKey", "ABC-1"));

            assertThat(result.isError()).isFalse();
            assertThat(json(result).at("/issueKey").asString()).isEqualTo("ABC-1");
            assertThat(json(result).at("/deleted").asBoolean()).isTrue();
        }
        assertThat(BACKEND.deletes()).containsExactly("/rest/api/2/issue/ABC-1");
        assertThat(BACKEND.writes()).singleElement().satisfies(request -> {
            assertThat(request.method()).isEqualTo("DELETE");
            assertThat(request.authorization()).isEqualTo(PAT);
        });
    }

    @Test
    void isListedAsDestructive() {
        McpSchema.Tool tool = listedTool("delete_issue");
        assertThat(tool.annotations().destructiveHint()).isTrue();
        assertThat(tool.annotations().readOnlyHint()).isFalse();
        assertThat(tool.title()).isEqualTo("Usuń zgłoszenie");
    }

    @Test
    void withoutTheDestructiveGroupTheCallIsDeniedAndNothingReachesJira() {
        try (McpSyncClient client = clientWithCredential(PAT)) {
            McpSchema.CallToolResult result = call(client, "delete_issue", Map.of("issueKey", "ABC-1"));

            assertThat(result.isError()).isTrue();
            assertThat(text(result)).contains("\"error\":\"access_denied\"");
        }
        assertThat(BACKEND.requests()).isEmpty();
    }

    @Test
    void theDestructiveGroupWithoutTheProjectScopeDoesNotDeleteInThatProject() {
        try (McpSyncClient client = clientWithToken(user -> user.username("jkowalski")
                .groups(java.util.List.of(READ_GROUP, WRITE_GROUP, DESTRUCTIVE_GROUP, PROJECT_DEF_GROUP))
                .mfaAgeSeconds(60), PAT)) {
            McpSchema.CallToolResult result = call(client, "delete_issue", Map.of("issueKey", "ABC-1"));

            assertThat(result.isError()).isTrue();
            assertThat(text(result)).contains(NOT_FOUND_OR_DENIED);
        }
        assertThat(BACKEND.deletes()).isEmpty();
    }

    @Test
    void anIssueOfARestrictedProjectLooksLikeAMissingOneAndIsNotDeleted() {
        try (McpSyncClient client = eraserClient(PAT)) {
            McpSchema.CallToolResult missing = call(client, "delete_issue", Map.of("issueKey", "ABC-999"));
            McpSchema.CallToolResult restricted = call(client, "delete_issue", Map.of("issueKey", "HR-1"));
            McpSchema.CallToolResult moved = call(client, "delete_issue", Map.of("issueKey", "ABC-30"));

            assertThat(restricted.isError()).isTrue();
            assertThat(text(restricted)).contains(NOT_FOUND_OR_DENIED).isEqualTo(text(missing));
            assertThat(text(moved)).as("moved to the restricted project: judged by where it is now").isEqualTo(text(missing));
        }
        assertThat(BACKEND.deletes()).isEmpty();
    }

    @Test
    void aForbiddenIssueIsIndistinguishableFromAMissingOne() {
        try (McpSyncClient client = eraserClient(PAT)) {
            McpSchema.CallToolResult missing = call(client, "delete_issue", Map.of("issueKey", "ABC-999"));
            McpSchema.CallToolResult forbidden = call(client, "delete_issue", Map.of("issueKey", "ABC-403"));
            McpSchema.CallToolResult cannotDelete = call(client, "delete_issue", Map.of("issueKey", "ABC-24"));

            assertThat(text(forbidden)).contains(NOT_FOUND_OR_DENIED).isEqualTo(text(missing));
            assertThat(text(cannotDelete)).as("403 on the DELETE itself").isEqualTo(text(missing));
        }
    }

    @Test
    void anIssueThatVanishesBetweenTheReadAndTheDeleteIsNotFoundOrDenied() {
        try (McpSyncClient client = eraserClient(PAT)) {
            assertNotFoundOrDenied(call(client, "delete_issue", Map.of("issueKey", "ABC-20")));
        }
        assertThat(BACKEND.deletes()).containsExactly("/rest/api/2/issue/ABC-20");
    }

    @Test
    void aMovedIssueIsNotDeletedUnderAKeyTheHumanDidNotConfirm() {
        try (McpSyncClient client = eraserClient(PAT)) {
            McpSchema.CallToolResult moved = call(client, "delete_issue", Map.of("issueKey", "ABC-3"));

            assertInvalidArgument(moved, "The issue now has the key DEF-5; call again with that key.");
            assertThat(BACKEND.deletes()).isEmpty();

            McpSchema.CallToolResult again = call(client, "delete_issue", Map.of("issueKey", "DEF-5"));
            assertThat(json(again).at("/issueKey").asString()).isEqualTo("DEF-5");
        }
        assertThat(BACKEND.deletes()).containsExactly("/rest/api/2/issue/DEF-5");
    }

    @Test
    void theNewKeyOfAMovedIssueOutsideThePolicyIsNotDisclosed() {
        try (McpSyncClient client = eraserClient(PAT)) {
            McpSchema.CallToolResult missing = call(client, "delete_issue", Map.of("issueKey", "ABC-999"));
            McpSchema.CallToolResult movedToHr = call(client, "delete_issue", Map.of("issueKey", "ABC-30"));

            assertThat(text(movedToHr)).isEqualTo(text(missing)).doesNotContain("HR-2");
        }
    }

    @Test
    void withoutCredentialAsksToConnectTheAccount() {
        try (McpSyncClient client = eraserClient(null)) {
            assertCredentialRequired(call(client, "delete_issue", Map.of("issueKey", "ABC-1")));
        }
        assertThat(BACKEND.requests()).isEmpty();
    }

    @Test
    void aPatRejectedByJiraAsksToReconnect() {
        try (McpSyncClient client = eraserClient(StubBackend.EXPIRED_CREDENTIAL)) {
            assertCredentialRequired(call(client, "delete_issue", Map.of("issueKey", "ABC-1")));
        }
        assertThat(BACKEND.deletes()).isEmpty();
    }

    @Test
    void aPatRejectedOnTheDeleteItselfAsksToReconnect() {
        try (McpSyncClient client = eraserClient(PAT)) {
            assertCredentialRequired(call(client, "delete_issue", Map.of("issueKey", "ABC-21")));
        }
        assertThat(BACKEND.deletes()).containsExactly("/rest/api/2/issue/ABC-21");
    }

    @Test
    void anIssueWithSubtasksIsAnInvalidArgumentWithoutJirasText() {
        try (McpSyncClient client = eraserClient(PAT)) {
            McpSchema.CallToolResult result = call(client, "delete_issue", Map.of("issueKey", "ABC-22"));

            assertInvalidArgument(result, "Jira refused the deletion");
            assertThat(text(result)).doesNotContain("ABC-23", "Subtask");
        }
    }

    @Test
    void anAnswerOtherThanNoContentIsNotADeletion() {
        try (McpSyncClient client = eraserClient(PAT)) {
            assertThat(text(call(client, "delete_issue", Map.of("issueKey", "ABC-25"))))
                    .contains("\"error\":\"internal_error\"");
        }
    }

    @Test
    void aRedirectIsNotFollowed() {
        try (McpSyncClient client = eraserClient(PAT)) {
            assertThat(text(call(client, "delete_issue", Map.of("issueKey", "ABC-26"))))
                    .contains("\"error\":\"internal_error\"");
            assertThat(BACKEND.elsewhereHits()).isEmpty();
        }
    }

    @Test
    void invalidKeysNeverReachJira() {
        try (McpSyncClient client = eraserClient(PAT)) {
            for (String key : new String[] {"abc-1", "ABC-1/comment", "ABC-0", "..", "ABC-1 OR 1=1"}) {
                assertInvalidArgument(call(client, "delete_issue", Map.of("issueKey", key)),
                        "issueKey does not match the required format");
            }
        }
        assertThat(BACKEND.requests()).isEmpty();
    }
}
