package pl.aiwcorpo.mcp.jira.app;

import static org.assertj.core.api.Assertions.assertThat;

import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.spec.McpSchema;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** {@code transition_issue}: only a transition Jira offers for the issue now is attempted. */
class TransitionIssueToolTest extends ServerTestSupport {

    @BeforeEach
    void resetBackend() {
        BACKEND.reset();
    }

    @Test
    void movesTheIssueByTransitionName() {
        try (McpSyncClient client = clientWithCredential(PAT)) {
            McpSchema.CallToolResult result = call(client, "transition_issue",
                    Map.of("issueKey", "ABC-1", "transition", "in progress"));

            assertThat(result.isError()).isFalse();
            assertThat(json(result).at("/transition").asString()).isEqualTo("In Progress");
            assertThat(json(result).at("/status").asString()).isEqualTo("In Progress");
        }
        assertThat(BACKEND.requests()).extracting(StubBackend.Request::method, StubBackend.Request::path)
                .containsExactly(org.assertj.core.groups.Tuple.tuple("GET", "/rest/api/2/issue/ABC-1"),
                        org.assertj.core.groups.Tuple.tuple("GET", "/rest/api/2/issue/ABC-1/transitions"),
                        org.assertj.core.groups.Tuple.tuple("POST", "/rest/api/2/issue/ABC-1/transitions"));
        assertThat(JSON.readTree(BACKEND.requests().get(2).body()).at("/transition/id").asString()).isEqualTo("21");
        assertThat(BACKEND.authorizations()).containsOnly(PAT);
    }

    @Test
    void movesTheIssueByTransitionId() {
        try (McpSyncClient client = clientWithCredential(PAT)) {
            McpSchema.CallToolResult result = call(client, "transition_issue",
                    Map.of("issueKey", "ABC-1", "transition", "11"));

            assertThat(json(result).at("/status").asString()).isEqualTo("To Do");
        }
    }

    @Test
    void isListedAsAnUpdateTool() {
        McpSchema.Tool tool = listedTool("transition_issue");
        assertThat(tool.annotations().readOnlyHint()).isFalse();
        assertThat(tool.annotations().destructiveHint()).isFalse();
    }

    @Test
    void aTransitionTheIssueDoesNotOfferIsNotAttempted() {
        try (McpSyncClient client = clientWithCredential(PAT)) {
            assertInvalidArgument(call(client, "transition_issue", Map.of("issueKey", "ABC-1", "transition", "Reopen")),
                    "no such transition");
        }
        assertThat(BACKEND.requests()).extracting(StubBackend.Request::method).containsExactly("GET", "GET");
    }

    @Test
    void aTransitionJiraRefusesIsAnInvalidArgumentWithoutJirasText() {
        try (McpSyncClient client = clientWithCredential(PAT)) {
            McpSchema.CallToolResult result = call(client, "transition_issue",
                    Map.of("issueKey", "ABC-1", "transition", "Done"));

            assertInvalidArgument(result, "Jira refused the transition");
            assertThat(text(result)).doesNotContain("Resolution");
        }
    }

    @Test
    void withoutCredentialAsksToConnectTheAccount() {
        try (McpSyncClient client = clientWithCredential(null)) {
            assertCredentialRequired(call(client, "transition_issue", Map.of("issueKey", "ABC-1", "transition", "21")));
        }
        assertThat(BACKEND.requests()).isEmpty();
    }

    @Test
    void aPatRejectedByJiraAsksToReconnect() {
        try (McpSyncClient client = clientWithCredential(StubBackend.EXPIRED_CREDENTIAL)) {
            assertCredentialRequired(call(client, "transition_issue", Map.of("issueKey", "ABC-1", "transition", "21")));
        }
    }

    @Test
    void missingAndForbiddenIssuesLookTheSame() {
        try (McpSyncClient client = clientWithCredential(PAT)) {
            McpSchema.CallToolResult missing = call(client, "transition_issue",
                    Map.of("issueKey", "ABC-404", "transition", "21"));
            McpSchema.CallToolResult forbidden = call(client, "transition_issue",
                    Map.of("issueKey", "ABC-403", "transition", "21"));

            assertNotFoundOrDenied(missing);
            assertThat(text(forbidden)).isEqualTo(text(missing));
        }
    }

    @Test
    void invalidArgumentsAreRejectedBeforeJira() {
        try (McpSyncClient client = clientWithCredential(PAT)) {
            assertInvalidArgument(call(client, "transition_issue", Map.of("issueKey", "ABC-1x", "transition", "21")),
                    "issueKey does not match the required format");
            assertInvalidArgument(call(client, "transition_issue", Map.of("issueKey", "ABC-1", "transition", "\"x\"")),
                    "transition must be");
            assertInvalidArgument(call(client, "transition_issue", Map.of("issueKey", "ABC-1", "transition", " Done")),
                    "transition must be");
        }
        assertThat(BACKEND.requests()).isEmpty();
    }

    @Test
    void aNameSharedByTwoTransitionsIsRefusedWithTheIdsToChooseFrom() {
        try (McpSyncClient client = clientWithCredential(PAT)) {
            McpSchema.CallToolResult result = call(client, "transition_issue",
                    Map.of("issueKey", "ABC-7", "transition", "Done"));

            assertInvalidArgument(result, "Several transitions have that name");
            assertThat(text(result)).contains("41 -> Done").contains("42 -> Rejected");
        }
        assertThat(BACKEND.requests()).extracting(StubBackend.Request::method).doesNotContain("POST");
    }

    @Test
    void theIdChosenAfterTheRefusalMovesTheIssue() {
        try (McpSyncClient client = clientWithCredential(PAT)) {
            McpSchema.CallToolResult result = call(client, "transition_issue",
                    Map.of("issueKey", "ABC-7", "transition", "42"));

            assertThat(json(result).at("/status").asString()).isEqualTo("Rejected");
        }
        assertThat(JSON.readTree(BACKEND.requests().getLast().body()).at("/transition/id").asString()).isEqualTo("42");
    }

    @Test
    void aNameIsMatchedWholeNotByItsStart() {
        try (McpSyncClient client = clientWithCredential(PAT)) {
            McpSchema.CallToolResult doing = call(client, "transition_issue",
                    Map.of("issueKey", "ABC-7", "transition", "do"));
            assertThat(json(doing).at("/status").asString()).isEqualTo("Doing");

            assertInvalidArgument(call(client, "transition_issue", Map.of("issueKey", "ABC-7", "transition", "Don")),
                    "no such transition");
        }
        assertThat(BACKEND.requests()).extracting(StubBackend.Request::method).containsOnlyOnce("POST");
        assertThat(JSON.readTree(BACKEND.requests().stream().filter(r -> "POST".equals(r.method())).findFirst()
                .orElseThrow().body()).at("/transition/id").asString()).isEqualTo("43");
    }

    @Test
    void aMovedIssueIsTransitionedUnderItsNewKeyAndReportedWithIt() {
        try (McpSyncClient client = clientWithCredential(PAT)) {
            McpSchema.CallToolResult result = call(client, "transition_issue",
                    Map.of("issueKey", "ABC-3", "transition", "21"));

            assertThat(json(result).at("/issueKey").asString()).isEqualTo("DEF-5");
        }
        assertThat(BACKEND.requests()).extracting(StubBackend.Request::method, StubBackend.Request::path)
                .containsExactly(org.assertj.core.groups.Tuple.tuple("GET", "/rest/api/2/issue/ABC-3"),
                        org.assertj.core.groups.Tuple.tuple("GET", "/rest/api/2/issue/DEF-5/transitions"),
                        org.assertj.core.groups.Tuple.tuple("POST", "/rest/api/2/issue/DEF-5/transitions"));
    }

    @Test
    void aRedirectIsNotAnExecutedTransition() {
        try (McpSyncClient client = clientWithCredential(PAT)) {
            McpSchema.CallToolResult result = call(client, "transition_issue",
                    Map.of("issueKey", "ABC-8", "transition", "21"));

            assertThat(result.isError()).isTrue();
            assertThat(text(result)).contains("\"error\":\"internal_error\"");
            assertThat(BACKEND.elsewhereHits()).as("the PAT must not follow the Location").isEmpty();
        }
    }

    @Test
    void anAnswerOtherThanNoContentIsNotAnExecutedTransition() {
        try (McpSyncClient client = clientWithCredential(PAT)) {
            assertThat(text(call(client, "transition_issue", Map.of("issueKey", "ABC-15", "transition", "21"))))
                    .contains("\"error\":\"internal_error\"");
        }
    }
}
