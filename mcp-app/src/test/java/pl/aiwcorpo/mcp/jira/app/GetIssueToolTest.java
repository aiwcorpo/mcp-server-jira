package pl.aiwcorpo.mcp.jira.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.spec.McpSchema;
import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import pl.aiwcorpo.mcp.jira.core.port.out.JiraResourcePort;
import pl.aiwcorpo.mcp.platform.security.CredentialRequiredException;

/** {@code get_issue} through the test gateway: the C3 PAT reaches Jira, errors are C10. */
@ExtendWith(OutputCaptureExtension.class)
class GetIssueToolTest extends ServerTestSupport {

    @Autowired
    JiraResourcePort jira;

    @BeforeEach
    void resetBackend() {
        BACKEND.reset();
    }

    @Test
    void readsTheIssueWithThePatFromTheGateway() {
        try (McpSyncClient client = clientWithCredential(PAT)) {
            McpSchema.CallToolResult result = call(client, "get_issue", Map.of("issueKey", "ABC-1"));

            assertThat(result.isError()).isFalse();
            assertThat(json(result).at("/key").asString()).isEqualTo("ABC-1");
            assertThat(json(result).at("/project").asString()).isEqualTo("ABC");
            assertThat(json(result).at("/status").asString()).isEqualTo("To Do");
            assertThat(json(result).at("/type").asString()).isEqualTo("Task");
            assertThat(json(result).at("/summary/text").asString()).isEqualTo("Login page returns 500");
            assertThat(json(result).at("/summary/kind").asString()).isEqualTo("untrusted-data");
            assertThat(json(result).at("/summary/format").asString()).isEqualTo("text/plain");
            assertThat(json(result).at("/summary/truncated").asBoolean()).isFalse();
            assertThat(json(result).at("/description/text").asString()).startsWith("Test issue ABC-1.");
            assertThat(text(result)).as("only the fields of the tool leave the adapter")
                    .doesNotContain("alice@corp.test", "Salary review", "HR-1", "<p>", "jira.internal");
        }
        assertThat(BACKEND.requests()).as("the issue, then the category of its project, nothing more").hasSize(2);
        assertThat(BACKEND.requests().getFirst()).satisfies(request -> {
            assertThat(request.authorization()).isEqualTo(PAT);
            assertThat(request.path()).isEqualTo("/rest/api/2/issue/ABC-1");
            assertThat(request.query()).isEqualTo("fields=summary,description,status,issuetype,assignee,project,updated");
        });
        assertThat(BACKEND.requests().getLast().path()).isEqualTo("/rest/api/2/project/ABC");
        assertThat(BACKEND.authorizations()).containsOnly(PAT);
    }

    @Test
    void anUnassignedIssueIsAnOkResultWithNullAssignee() {
        try (McpSyncClient client = clientWithCredential(PAT)) {
            McpSchema.CallToolResult result = call(client, "get_issue", Map.of("issueKey", "ABC-3"));

            assertThat(result.isError()).isFalse();
            assertThat(json(result).path("assignee").isNull()).isTrue();
            assertThat(json(result).path("description").isNull()).isTrue();
        }
    }

    @Test
    void theDescriptionIsPlainTextWithoutWikiMarkupImagesAndMacros() {
        try (McpSyncClient client = clientWithCredential(PAT)) {
            McpSchema.CallToolResult result = call(client, "get_issue", Map.of("issueKey", "ABC-18"));

            assertThat(result.isError()).isFalse();
            assertThat(json(result).at("/description/format").asString()).isEqualTo("text/plain");
            assertThat(json(result).at("/description/text").asString()).isEqualTo("""
                    Steps
                    - open the login page
                    - see

                    int a = 1;

                    Ask bob or read the guide (https://wiki.corp.test/g) and https://wiki.corp.test/h. Hidden: \
                    !https://evil.test/q.png ! and""");
            assertThat(text(result)).doesNotContain("r.png", "{code", "[~");
        }
    }

    @Test
    void acceptsTheVirtualServerClaimsOfACallThroughALink() {
        try (McpSyncClient client = clientWithToken(
                user -> { reader(user); user.virtualServer("team-delivery", Map.of()); }, PAT)) {
            McpSchema.CallToolResult result = call(client, "get_issue", Map.of("issueKey", "ABC-1"));

            assertThat(result.isError()).isFalse();
            assertThat(json(result).at("/key").asString()).isEqualTo("ABC-1");
        }
    }

    @Test
    void aMovedIssueIsReportedUnderItsNewKeyAndProject() {
        try (McpSyncClient client = clientWithCredential(PAT)) {
            McpSchema.CallToolResult result = call(client, "get_issue", Map.of("issueKey", "ABC-3"));

            assertThat(json(result).at("/key").asString()).isEqualTo("DEF-5");
            assertThat(json(result).at("/project").asString()).isEqualTo("DEF");
        }
    }

    @Test
    void withoutCredentialAsksToConnectTheAccountAndDoesNotCallJira() {
        try (McpSyncClient client = clientWithCredential(null)) {
            assertCredentialRequired(call(client, "get_issue", Map.of("issueKey", "ABC-1")));
        }
        assertThat(BACKEND.requests()).isEmpty();
    }

    @Test
    void aPatRejectedByJiraAsksToReconnect(CapturedOutput output) {
        try (McpSyncClient client = clientWithCredential(StubBackend.EXPIRED_CREDENTIAL)) {
            assertCredentialRequired(call(client, "get_issue", Map.of("issueKey", "ABC-1")));
        }
        assertThat(output.getAll()).contains("CredentialRequiredException").doesNotContain("expired-pat");
    }

    @Test
    void theAdapterHasNoCredentialOutsideAGatewayRequest() {
        assertThatThrownBy(() -> jira.findIssue("ABC-1")).isInstanceOf(CredentialRequiredException.class);
        assertThat(BACKEND.requests()).isEmpty();
    }

    @Test
    void missingAndForbiddenIssuesLookTheSame() {
        try (McpSyncClient client = clientWithCredential(PAT)) {
            McpSchema.CallToolResult missing = call(client, "get_issue", Map.of("issueKey", "ABC-404"));
            McpSchema.CallToolResult forbidden = call(client, "get_issue", Map.of("issueKey", "ABC-403"));

            assertNotFoundOrDenied(missing);
            assertThat(text(forbidden)).isEqualTo(text(missing));
        }
    }

    @Test
    void anInvalidIssueKeyIsRejectedBeforeJira() {
        try (McpSyncClient client = clientWithCredential(PAT)) {
            for (String key : new String[] {"abc-1", "ABC-0", "ABC-01", "ABC_1", "ABC-1/comment", "../ABC-1",
                    "ABC-1?x=1", "ABCDEFGHIJK-1"}) {
                assertInvalidArgument(call(client, "get_issue", Map.of("issueKey", key)), "issueKey does not match the required format");
            }
        }
        assertThat(BACKEND.requests()).isEmpty();
    }

    @Test
    void argumentsOutsideTheSchemaNeverReachJira() {
        try (McpSyncClient client = clientWithCredential(PAT)) {
            assertInvalidArgument(call(client, "get_issue", Map.of("issueKey", "ABC-1", "fields", "*all")),
                    "unknown arguments are not allowed");
            assertInvalidArgument(call(client, "get_issue", Map.of("issueKey", "ABC-1" + "0".repeat(30))),
                    "issueKey is longer than 21 characters");
            assertInvalidArgument(call(client, "get_issue", Map.of()), "issueKey is required");
            assertInvalidArgument(call(client, "get_issue", Map.of("issueKey", "A-1")),
                    "issueKey is shorter than 4 characters");
        }
        assertThat(BACKEND.requests()).isEmpty();
    }

    @Test
    void thePatNeverAppearsInTheResultOrTheLog(CapturedOutput output) {
        try (McpSyncClient client = clientWithCredential(PAT)) {
            McpSchema.CallToolResult result = call(client, "get_issue", Map.of("issueKey", "ABC-500"));

            assertThat(result.isError()).isTrue();
            assertThat(text(result)).contains("\"error\":\"internal_error\"").doesNotContain("jira-pat");
        }
        assertThat(output.getAll()).contains("\"tool\":\"get_issue\"").doesNotContain("jira-pat")
                .as("Jira's error body stays out of the log").doesNotContain("boom");
    }

    @Test
    void anUnknownArgumentNameStaysOutOfTheResultAndTheLog(CapturedOutput output) {
        String name = "zzForgedArgument";
        try (McpSyncClient client = clientWithCredential(PAT)) {
            McpSchema.CallToolResult result = call(client, "get_issue", Map.of("issueKey", "ABC-1", name, "x"));

            assertInvalidArgument(result, "unknown arguments are not allowed");
            assertThat(text(result)).doesNotContain(name);
        }
        assertThat(output.getAll()).doesNotContain(name);
        assertThat(BACKEND.requests()).isEmpty();
    }

    @Test
    void anUnreadableBodyStaysOutOfTheResultAndTheLog(CapturedOutput output) {
        try (McpSyncClient client = clientWithCredential(PAT)) {
            McpSchema.CallToolResult result = call(client, "get_issue", Map.of("issueKey", "ABC-10"));

            assertThat(result.isError()).isTrue();
            assertThat(text(result)).contains("\"error\":\"internal_error\"").doesNotContain(StubBackend.BODY_MARKER);
        }
        assertThat(output.getAll()).contains("BackendResponseException").doesNotContain(StubBackend.BODY_MARKER);
    }

    @Test
    void anEmptyAnswerIsTheBackendsFaultNotAMissingIssue() {
        try (McpSyncClient client = clientWithCredential(PAT)) {
            assertThat(text(call(client, "get_issue", Map.of("issueKey", "ABC-11"))))
                    .contains("\"error\":\"internal_error\"");
        }
    }

    @Test
    void anIssueWithoutKeyIsTheBackendsFault() {
        try (McpSyncClient client = clientWithCredential(PAT)) {
            assertThat(text(call(client, "get_issue", Map.of("issueKey", "ABC-12"))))
                    .contains("\"error\":\"internal_error\"");
        }
    }

    @Test
    void aRedirectIsNotFollowedAndNotReadAsMissing() {
        try (McpSyncClient client = clientWithCredential(PAT)) {
            McpSchema.CallToolResult result = call(client, "get_issue", Map.of("issueKey", "ABC-14"));

            assertThat(text(result)).contains("\"error\":\"internal_error\"");
            assertThat(BACKEND.elsewhereHits()).as("the PAT must not follow the Location").isEmpty();
        }
    }

    @Test
    void aSlowJiraTimesOut() {
        try (McpSyncClient client = clientWithCredential(PAT)) {
            long start = System.nanoTime();
            McpSchema.CallToolResult result = call(client, "get_issue", Map.of("issueKey", "ABC-16"));

            assertThat(text(result)).contains("\"error\":\"upstream_unavailable\"");
            assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(Duration.ofMillis(StubBackend.SLOW_MILLIS));
        }
    }

    @Test
    void anOversizedBodyIsCutOff() {
        try (McpSyncClient client = clientWithCredential(PAT)) {
            assertThat(text(call(client, "get_issue", Map.of("issueKey", "ABC-13"))))
                    .contains("\"error\":\"internal_error\"");
        }
    }
}
