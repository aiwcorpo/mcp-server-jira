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
                .containsExactly(org.assertj.core.groups.Tuple.tuple("GET", "/rest/api/2/issue/ABC-1/transitions"),
                        org.assertj.core.groups.Tuple.tuple("POST", "/rest/api/2/issue/ABC-1/transitions"));
        assertThat(JSON.readTree(BACKEND.requests().get(1).body()).at("/transition/id").asString()).isEqualTo("21");
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
        assertThat(BACKEND.requests()).extracting(StubBackend.Request::method).containsExactly("GET");
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
                    "issueKey must be");
            assertInvalidArgument(call(client, "transition_issue", Map.of("issueKey", "ABC-1", "transition", "\"x\"")),
                    "transition must be");
            assertInvalidArgument(call(client, "transition_issue", Map.of("issueKey", "ABC-1", "transition", " Done")),
                    "transition must be");
        }
        assertThat(BACKEND.requests()).isEmpty();
    }
}
