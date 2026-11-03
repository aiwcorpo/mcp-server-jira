package pl.aiwcorpo.mcp.jira.app;

import static org.assertj.core.api.Assertions.assertThat;

import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.spec.McpSchema;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/** The UPDATE tools added for the full Data Center catalog: what reaches Jira, what is refused first, how Jira's refusals look. */
class WriteToolsTest extends ServerTestSupport {

    private static final Map<String, Map<String, Object>> NEW_WRITES = Map.of(
            "create_issue", Map.of("project", "ABC", "issueType", "Task", "summary", "s"),
            "update_issue", Map.of("issueKey", "ABC-1", "summary", "s"),
            "assign_issue", Map.of("issueKey", "ABC-1", "assignee", "alice"),
            "unassign_issue", Map.of("issueKey", "ABC-1"),
            "link_issues", Map.of("outwardIssueKey", "ABC-1", "inwardIssueKey", "DEF-5", "linkType", "Blocks"),
            "add_worklog", Map.of("issueKey", "ABC-1", "timeSpent", "1h"),
            "edit_comment", Map.of("issueKey", "ABC-1", "commentId", "10500", "body", "x"));

    @BeforeEach
    void resetBackend() {
        BACKEND.reset();
    }

    private static StubBackend.Request lastWrite() {
        return BACKEND.writes().getLast();
    }

    @Test
    void createsAnIssueAsTheUserWithTheDescriptionAsLiteralText() {
        try (McpSyncClient client = clientWithCredential(PAT)) {
            McpSchema.CallToolResult result = call(client, "create_issue", Map.of("project", "ABC", "issueType", "Bug",
                    "summary", "Login fails", "description", "Steps: !https://evil.test/p.png! and [~admin]",
                    "labels", List.of("backend", "p1"), "priority", "High", "assignee", "alice"));

            assertThat(result.isError()).isFalse();
            assertThat(json(result).at("/issueKey").asString()).isEqualTo("ABC-901");
            assertThat(json(result).at("/project").asString()).isEqualTo("ABC");
        }
        assertThat(lastWrite().method()).isEqualTo("POST");
        assertThat(lastWrite().path()).isEqualTo("/rest/api/2/issue");
        assertThat(lastWrite().authorization()).isEqualTo(PAT);
        JsonNode fields = JSON.readTree(lastWrite().body()).at("/fields");
        assertThat(fields.at("/project/key").asString()).isEqualTo("ABC");
        assertThat(fields.at("/issuetype/name").asString()).isEqualTo("Bug");
        assertThat(fields.at("/summary").asString()).isEqualTo("Login fails");
        assertThat(fields.at("/description").asString())
                .isEqualTo("{noformat}\nSteps: !https://evil.test/p.png! and [~admin]\n{noformat}");
        assertThat(fields.at("/labels")).extracting(JsonNode::asString).containsExactly("backend", "p1");
        assertThat(fields.at("/priority/name").asString()).isEqualTo("High");
        assertThat(fields.at("/assignee/name").asString()).isEqualTo("alice");
    }

    @Test
    void anIssueNeedsOnlyProjectTypeAndSummary() {
        try (McpSyncClient client = clientWithCredential(PAT)) {
            assertThat(call(client, "create_issue", Map.of("project", "DEF", "issueType", "Task", "summary", "x")).isError())
                    .isFalse();
        }
        JsonNode fields = JSON.readTree(lastWrite().body()).at("/fields");
        assertThat(fields.has("description")).isFalse();
        assertThat(fields.has("labels")).isFalse();
        assertThat(fields.has("priority")).isFalse();
        assertThat(fields.has("assignee")).isFalse();
    }

    @Test
    void aProjectTheCallerMayNotChangeLooksLikeOneThatDoesNotExist() {
        try (McpSyncClient client = clientWithCredential(PAT)) {
            McpSchema.CallToolResult restricted = call(client, "create_issue",
                    Map.of("project", "HR", "issueType", "Task", "summary", "x"));
            McpSchema.CallToolResult missing = call(client, "create_issue",
                    Map.of("project", "NOPE", "issueType", "Task", "summary", "x"));

            assertThat(restricted.isError()).isTrue();
            assertThat(text(restricted)).as("no project grant: the call is denied before Jira is asked")
                    .contains("\"error\":\"access_denied\"");
            assertThat(text(missing)).as("a project that does not exist answers the same").isEqualTo(text(restricted));
        }
        assertThat(BACKEND.requests()).isEmpty();
    }

    @Test
    void jirasRefusalOfAnIssueIsAnInvalidArgumentWithoutJirasText() {
        try (McpSyncClient client = clientWithCredential(PAT)) {
            McpSchema.CallToolResult result = call(client, "create_issue",
                    Map.of("project", "ABC", "issueType", "Nope", "summary", "x"));

            assertInvalidArgument(result, "Jira refused the issue");
            assertThat(text(result)).doesNotContain("valid issue type is required");
        }
    }

    @Test
    void createIssueRefusesBadInputBeforeJira() {
        try (McpSyncClient client = clientWithCredential(PAT)) {
            assertInvalidArgument(call(client, "create_issue", Map.of("project", "ABC", "issueType", "Task", "summary", "a\nb")),
                    "summary must be");
            assertInvalidArgument(call(client, "create_issue", Map.of("project", "ABC", "issueType", "Task", "summary", "  ")),
                    "summary must be");
            assertInvalidArgument(call(client, "create_issue", Map.of("project", "ABC", "issueType", "Ta\"sk", "summary", "x")),
                    "issueType must be");
            assertInvalidArgument(call(client, "create_issue", Map.of("project", "ABC", "issueType", "Task", "summary", "x",
                    "labels", List.of("has space"))), "labels");
            assertInvalidArgument(call(client, "create_issue", Map.of("project", "ABC", "issueType", "Task", "summary", "x",
                    "labels", List.of("a", "b", "c", "d", "e", "f", "g", "h", "i", "j", "k"))), "labels");
            assertInvalidArgument(call(client, "create_issue", Map.of("project", "ABC", "issueType", "Task", "summary", "x",
                    "description", "x".repeat(10_001))), "description is longer than 10000");
            assertInvalidArgument(call(client, "create_issue", Map.of("project", "ABC", "issueType", "Task", "summary", "x",
                    "assignee", "alice OR 1=1")), "assignee must be");
            assertInvalidArgument(call(client, "create_issue", Map.of("project", "ABC", "issueType", "Task", "summary", "x",
                    "customfield_10000", "y")), "unknown arguments are not allowed");
            assertInvalidArgument(call(client, "create_issue", Map.of("project", "ABC", "issueType", "Task",
                    "summary", "x".repeat(256))), "summary is longer than 255");
        }
        assertThat(BACKEND.writes()).isEmpty();
    }

    @Test
    void updatesTheGivenFieldsOnTheIssueJiraReports() {
        try (McpSyncClient client = clientWithCredential(PAT)) {
            McpSchema.CallToolResult result = call(client, "update_issue", Map.of("issueKey", "ABC-3", "summary", "New title",
                    "description", "[x|https://evil.test]", "labels", List.of("a"), "priority", "High"));

            assertThat(result.isError()).isFalse();
            assertThat(json(result).at("/issueKey").asString()).as("ABC-3 was moved").isEqualTo("DEF-5");
            assertThat(json(result).at("/changed").asBoolean()).isTrue();
        }
        assertThat(lastWrite().method()).isEqualTo("PUT");
        assertThat(lastWrite().path()).isEqualTo("/rest/api/2/issue/DEF-5");
        JsonNode fields = JSON.readTree(lastWrite().body()).at("/fields");
        assertThat(fields.at("/summary").asString()).isEqualTo("New title");
        assertThat(fields.at("/description").asString()).isEqualTo("{noformat}\n[x|https://evil.test]\n{noformat}");
        assertThat(fields.at("/priority/name").asString()).isEqualTo("High");
    }

    @Test
    void anEmptyLabelListClearsTheLabelsAndOtherFieldsAreLeftAlone() {
        try (McpSyncClient client = clientWithCredential(PAT)) {
            assertThat(call(client, "update_issue", Map.of("issueKey", "ABC-1", "labels", List.of())).isError()).isFalse();
        }
        JsonNode fields = JSON.readTree(lastWrite().body()).at("/fields");
        assertThat(fields.has("labels")).isTrue();
        assertThat(fields.at("/labels")).isEmpty();
        assertThat(fields.has("summary")).isFalse();
        assertThat(fields.has("description")).isFalse();
    }

    @Test
    void updateIssueNeedsAtLeastOneField() {
        try (McpSyncClient client = clientWithCredential(PAT)) {
            assertInvalidArgument(call(client, "update_issue", Map.of("issueKey", "ABC-1")), "needs at least one of");
            assertInvalidArgument(call(client, "update_issue", Map.of("issueKey", "ABC-1", "summary", "a\u0000b")), "summary");
        }
        assertThat(BACKEND.writes()).isEmpty();
    }

    @Test
    void aValueJiraDoesNotAcceptIsAnInvalidArgumentWithoutJirasText() {
        try (McpSyncClient client = clientWithCredential(PAT)) {
            McpSchema.CallToolResult result = call(client, "update_issue", Map.of("issueKey", "ABC-1", "priority", "Nope"));

            assertInvalidArgument(result, "Jira refused the change");
            assertThat(text(result)).doesNotContain("Priority is not valid");
        }
    }

    @Test
    void assignsAndUnassigns() {
        try (McpSyncClient client = clientWithCredential(PAT)) {
            assertThat(call(client, "assign_issue", Map.of("issueKey", "ABC-1", "assignee", "alice")).isError()).isFalse();
            assertThat(lastWrite().path()).isEqualTo("/rest/api/2/issue/ABC-1/assignee");
            assertThat(JSON.readTree(lastWrite().body()).at("/name").asString()).isEqualTo("alice");

            assertThat(call(client, "unassign_issue", Map.of("issueKey", "ABC-1")).isError()).isFalse();
            assertThat(JSON.readTree(lastWrite().body()).get("name").isNull()).isTrue();

            McpSchema.CallToolResult ghost = call(client, "assign_issue", Map.of("issueKey", "ABC-1", "assignee", "ghost"));
            assertInvalidArgument(ghost, "Jira refused the assignment");
            assertThat(text(ghost)).doesNotContain("does not exist");
            assertInvalidArgument(call(client, "assign_issue", Map.of("issueKey", "ABC-1", "assignee", "a b")),
                    "assignee must be");
        }
    }

    @Test
    void linksTwoIssuesTheCallerMayChange() {
        try (McpSyncClient client = clientWithCredential(PAT)) {
            McpSchema.CallToolResult result = call(client, "link_issues",
                    Map.of("outwardIssueKey", "ABC-1", "inwardIssueKey", "DEF-5", "linkType", "Blocks"));

            assertThat(result.isError()).isFalse();
            assertThat(json(result).at("/outwardIssueKey").asString()).isEqualTo("ABC-1");
            assertThat(json(result).at("/inwardIssueKey").asString()).isEqualTo("DEF-5");
        }
        assertThat(lastWrite().path()).isEqualTo("/rest/api/2/issueLink");
        JsonNode body = JSON.readTree(lastWrite().body());
        assertThat(body.at("/type/name").asString()).isEqualTo("Blocks");
        assertThat(body.at("/outwardIssue/key").asString()).isEqualTo("ABC-1");
        assertThat(body.at("/inwardIssue/key").asString()).isEqualTo("DEF-5");
    }

    @Test
    void bothIssuesAreJudgedAndAnInvisibleOneRefusesTheWholeCall() {
        try (McpSyncClient client = clientWithCredential(PAT)) {
            assertNotFoundOrDenied(call(client, "link_issues",
                    Map.of("outwardIssueKey", "ABC-1", "inwardIssueKey", "HR-1", "linkType", "Relates")));
            assertNotFoundOrDenied(call(client, "link_issues",
                    Map.of("outwardIssueKey", "ABC-404", "inwardIssueKey", "ABC-1", "linkType", "Relates")));
            assertInvalidArgument(call(client, "link_issues",
                    Map.of("outwardIssueKey", "ABC-1", "inwardIssueKey", "ABC-1", "linkType", "Relates")), "cannot be linked to itself");
            assertNotFoundOrDenied(call(client, "link_issues",
                    Map.of("outwardIssueKey", "ABC-1", "inwardIssueKey", "DEF-5", "linkType", "Nope")));
        }
        assertThat(BACKEND.writes().stream().filter(r -> r.path().endsWith("/issueLink"))).hasSize(1);
    }

    @Test
    void logsWorkWithTheCommentAsLiteralText() {
        try (McpSyncClient client = clientWithCredential(PAT)) {
            McpSchema.CallToolResult result = call(client, "add_worklog",
                    Map.of("issueKey", "ABC-1", "timeSpent", "1h 30m", "comment", "pairing [~bob]"));

            assertThat(result.isError()).isFalse();
            assertThat(json(result).at("/worklogId").asString()).isEqualTo("10701");
            assertThat(text(result)).doesNotContain("jkowalski@corp.test", "(echo)");
        }
        JsonNode body = JSON.readTree(lastWrite().body());
        assertThat(lastWrite().path()).isEqualTo("/rest/api/2/issue/ABC-1/worklog");
        assertThat(body.at("/timeSpent").asString()).isEqualTo("1h 30m");
        assertThat(body.at("/comment").asString()).isEqualTo("{noformat}\npairing [~bob]\n{noformat}");
    }

    @Test
    void theTimeFormatIsCheckedBeforeJiraAndJirasRefusalIsFixedText() {
        try (McpSyncClient client = clientWithCredential(PAT)) {
            for (String bad : new String[] {"1 hour", "1h; drop", "-5m", "h", "1h 2h 3h 4h 5h", "1.5h"}) {
                assertInvalidArgument(call(client, "add_worklog", Map.of("issueKey", "ABC-1", "timeSpent", bad)), "timeSpent");
            }
            assertThat(BACKEND.writes()).isEmpty();
            McpSchema.CallToolResult refused = call(client, "add_worklog", Map.of("issueKey", "ABC-1", "timeSpent", "0m"));
            assertInvalidArgument(refused, "Jira refused the work log entry");
            assertThat(text(refused)).doesNotContain("Invalid time");
        }
    }

    @Test
    void editsACommentAsLiteralText() {
        try (McpSyncClient client = clientWithCredential(PAT)) {
            McpSchema.CallToolResult result = call(client, "edit_comment",
                    Map.of("issueKey", "ABC-1", "commentId", "10500", "body", "fixed !x!"));

            assertThat(result.isError()).isFalse();
            assertThat(json(result).at("/commentId").asString()).isEqualTo("10500");
            assertNotFoundOrDenied(call(client, "edit_comment",
                    Map.of("issueKey", "ABC-1", "commentId", "99999", "body", "x")));
            assertInvalidArgument(call(client, "edit_comment", Map.of("issueKey", "ABC-1", "commentId", "1/../2", "body", "x")),
                    "commentId does not match");
        }
        StubBackend.Request put = BACKEND.writes().stream().filter(r -> "PUT".equals(r.method())).findFirst().orElseThrow();
        assertThat(put.method()).isEqualTo("PUT");
        assertThat(put.path()).isEqualTo("/rest/api/2/issue/ABC-1/comment/10500");
        assertThat(JSON.readTree(put.body()).at("/body").asString()).isEqualTo("{noformat}\nfixed !x!\n{noformat}");
    }

    @Test
    void aCommentOfAnotherIssueIsNotEditedThroughThisIssue() {
        try (McpSyncClient client = clientWithCredential(PAT)) {
            McpSchema.CallToolResult elsewhere = call(client, "edit_comment",
                    Map.of("issueKey", "ABC-1", "commentId", "10600", "body", "overwritten"));
            McpSchema.CallToolResult missing = call(client, "edit_comment",
                    Map.of("issueKey", "ABC-1", "commentId", "99999", "body", "overwritten"));

            assertNotFoundOrDenied(elsewhere);
            assertThat(text(elsewhere)).isEqualTo(text(missing));
        }
        assertThat(BACKEND.writes()).as("nothing is written to a comment that is not under the judged issue").isEmpty();
    }

    @Test
    void aRestrictedCommentKeepsItsRestrictionWhenItIsEdited() {
        try (McpSyncClient client = clientWithCredential(PAT)) {
            assertThat(call(client, "edit_comment", Map.of("issueKey", "ABC-1", "commentId", "10700", "body", "new")).isError())
                    .isFalse();
            assertThat(call(client, "edit_comment", Map.of("issueKey", "ABC-1", "commentId", "10500", "body", "new")).isError())
                    .isFalse();
        }
        List<StubBackend.Request> puts = BACKEND.writes().stream().filter(r -> "PUT".equals(r.method())).toList();
        JsonNode restricted = JSON.readTree(puts.get(0).body());
        assertThat(restricted.at("/visibility/type").asString()).isEqualTo("role");
        assertThat(restricted.at("/visibility/value").asString()).isEqualTo("Developers");
        assertThat(restricted.at("/visibility/identifier").asString()).isEqualTo("10002");
        assertThat(JSON.readTree(puts.get(1).body()).has("visibility")).as("an open comment stays open").isFalse();
    }

    @Test
    void aRestrictionTheServerCannotReproduceStopsTheEdit() {
        try (McpSyncClient client = clientWithCredential(PAT)) {
            McpSchema.CallToolResult result = call(client, "edit_comment",
                    Map.of("issueKey", "ABC-1", "commentId", "10800", "body", "new"));

            assertInvalidArgument(result, "visibility restriction this tool cannot reproduce");
        }
        assertThat(BACKEND.writes()).as("nothing is written, the restriction is not dropped").isEmpty();
    }

    @Test
    void aKeyJiraReturnsForANewIssueMustLookLikeAKey() {
        try (McpSyncClient client = clientWithCredential(PAT)) {
            McpSchema.CallToolResult result = call(client, "create_issue",
                    Map.of("project", "ABC", "issueType", "Bad", "summary", "x"));

            assertThat(result.isError()).isTrue();
            assertThat(text(result)).contains("\"error\":\"internal_error\"").doesNotContain("Ignore previous");
        }
    }

    @Test
    void everyWriteToolIsRefusedWithoutTheWriteGroupAndNothingReachesJira() {
        try (McpSyncClient reader = clientWithToken(ServerTestSupport::reader, PAT)) {
            NEW_WRITES.forEach((tool, arguments) -> {
                McpSchema.CallToolResult result = call(reader, tool, arguments);
                assertThat(result.isError()).as(tool).isTrue();
                assertThat(text(result)).as(tool).contains("\"error\":\"access_denied\"");
            });
        }
        assertThat(BACKEND.requests()).isEmpty();
    }

    @Test
    void everyWriteToolAsksToConnectTheAccountWithoutACredentialOrWithARevokedOne() {
        try (McpSyncClient none = clientWithCredential(null);
                McpSyncClient revoked = clientWithCredential(StubBackend.EXPIRED_CREDENTIAL)) {
            NEW_WRITES.forEach((tool, arguments) -> {
                assertCredentialRequired(call(none, tool, arguments));
                assertCredentialRequired(call(revoked, tool, arguments));
            });
        }
        assertThat(BACKEND.writes()).isEmpty();
    }

    @Test
    void everyWriteToolOnAnInvisibleIssueAnswersLikeOnAMissingOne() {
        try (McpSyncClient client = clientWithCredential(PAT)) {
            for (String tool : List.of("update_issue", "assign_issue", "unassign_issue", "add_worklog", "edit_comment")) {
                Map<String, Object> missing = new java.util.HashMap<>(NEW_WRITES.get(tool));
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
}
