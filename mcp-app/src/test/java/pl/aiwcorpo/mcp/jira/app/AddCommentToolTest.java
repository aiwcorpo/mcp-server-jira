package pl.aiwcorpo.mcp.jira.app;

import static org.assertj.core.api.Assertions.assertThat;

import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.spec.McpSchema;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** {@code add_comment}: an UPDATE tool that writes as the calling user. */
class AddCommentToolTest extends ServerTestSupport {

    @BeforeEach
    void resetBackend() {
        BACKEND.reset();
    }

    @Test
    void addsTheCommentAsTheUser() {
        try (McpSyncClient client = clientWithCredential(PAT)) {
            McpSchema.CallToolResult result = call(client, "add_comment",
                    Map.of("issueKey", "ABC-1", "body", "Reproduced on staging.\nSee logs."));

            assertThat(result.isError()).isFalse();
            assertThat(json(result).at("/issueKey").asString()).isEqualTo("ABC-1");
            assertThat(json(result).at("/commentId").asString()).isEqualTo("10500");
            assertThat(text(result)).doesNotContain("jkowalski@corp.test", "(echo)");
        }
        assertThat(BACKEND.requests()).singleElement().satisfies(request -> {
            assertThat(request.method()).isEqualTo("POST");
            assertThat(request.path()).isEqualTo("/rest/api/2/issue/ABC-1/comment");
            assertThat(request.authorization()).isEqualTo(PAT);
            assertThat(JSON.readTree(request.body()).at("/body").asString())
                    .isEqualTo("Reproduced on staging.\nSee logs.");
        });
    }

    @Test
    void isListedAsAnUpdateTool() {
        McpSchema.Tool tool = listedTool("add_comment");
        assertThat(tool.annotations().readOnlyHint()).isFalse();
        assertThat(tool.annotations().destructiveHint()).isFalse();
    }

    @Test
    void withoutCredentialAsksToConnectTheAccount() {
        try (McpSyncClient client = clientWithCredential(null)) {
            assertCredentialRequired(call(client, "add_comment", Map.of("issueKey", "ABC-1", "body", "x")));
        }
        assertThat(BACKEND.requests()).isEmpty();
    }

    @Test
    void aPatRejectedByJiraAsksToReconnect() {
        try (McpSyncClient client = clientWithCredential(StubBackend.EXPIRED_CREDENTIAL)) {
            assertCredentialRequired(call(client, "add_comment", Map.of("issueKey", "ABC-1", "body", "x")));
        }
    }

    @Test
    void missingAndForbiddenIssuesLookTheSame() {
        try (McpSyncClient client = clientWithCredential(PAT)) {
            McpSchema.CallToolResult missing = call(client, "add_comment", Map.of("issueKey", "ABC-404", "body", "x"));
            McpSchema.CallToolResult forbidden = call(client, "add_comment", Map.of("issueKey", "ABC-403", "body", "x"));

            assertNotFoundOrDenied(missing);
            assertThat(text(forbidden)).isEqualTo(text(missing));
        }
    }

    @Test
    void invalidArgumentsAreRejectedBeforeJira() {
        try (McpSyncClient client = clientWithCredential(PAT)) {
            assertInvalidArgument(call(client, "add_comment", Map.of("issueKey", "abc-1", "body", "x")),
                    "issueKey must be");
            assertInvalidArgument(call(client, "add_comment", Map.of("issueKey", "ABC-1", "body", "   ")),
                    "body must be");
            assertInvalidArgument(call(client, "add_comment", Map.of("issueKey", "ABC-1", "body", "a\u0000b")),
                    "body must be");
            assertThat(call(client, "add_comment", Map.of("issueKey", "ABC-1", "body", "x".repeat(10_001))).isError())
                    .isTrue();
        }
        assertThat(BACKEND.requests()).isEmpty();
    }
}
