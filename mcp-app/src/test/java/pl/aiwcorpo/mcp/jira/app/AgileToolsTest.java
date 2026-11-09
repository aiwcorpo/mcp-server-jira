package pl.aiwcorpo.mcp.jira.app;

import static org.assertj.core.api.Assertions.assertThat;

import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.spec.McpSchema;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/**
 * Boards, sprints and sprint issues over the Agile REST API. Boards and sprints are not resources of the policy: a board
 * is judged by the projects it covers, a sprint by the board it was created on, and every issue by itself.
 */
class AgileToolsTest extends ServerTestSupport {

    @BeforeEach
    void resetBackend() {
        BACKEND.reset();
    }

    @Test
    void theBoardsOfAProjectLeaveOutThoseLocatedInARestrictedProject() {
        try (McpSyncClient client = clientWithCredential(PAT)) {
            McpSchema.CallToolResult result = call(client, "list_boards", Map.of("project", "ABC"));

            assertThat(result.isError()).isFalse();
            JsonNode page = json(result);
            assertThat(page.at("/items")).extracting(board -> board.get("id").asString()).containsExactly("7", "8");
            assertThat(page.at("/items/0/name/kind").asString()).isEqualTo("untrusted-data");
            assertThat(page.at("/items/0/name/text").asString()).isEqualTo("Alpha board");
            assertThat(page.at("/items/0/type").asString()).isEqualTo("scrum");
            assertThat(page.at("/items/0/project").asString()).isEqualTo("ABC");
            assertThat(page.at("/nextCursor").isNull() || page.at("/nextCursor").isMissingNode()).isTrue();
            assertThat(text(result)).doesNotContain("Payroll", "HR", "displayName", "projectId", "Orphan", "Odd");
        }
        StubBackend.Request read = BACKEND.requests().stream().filter(r -> r.path().equals("/rest/agile/1.0/board")).findFirst().orElseThrow();
        assertThat(read.query()).contains("projectKeyOrId=ABC", "startAt=0", "maxResults=25").doesNotContain("type=");
        assertThat(read.authorization()).isEqualTo(PAT);
    }

    @Test
    void theBoardTypeIsPassedOnAndCheckedBeforeJira() {
        try (McpSyncClient client = clientWithCredential(PAT)) {
            JsonNode page = json(call(client, "list_boards", Map.of("project", "ABC", "type", "kanban")));
            assertThat(page.at("/items")).extracting(board -> board.get("id").asString()).containsExactly("8");

            int before = BACKEND.requests().size();
            assertInvalidArgument(call(client, "list_boards", Map.of("project", "ABC", "type", "simple")), "type does not match");
            assertThat(BACKEND.requests()).hasSize(before);
        }
        assertThat(BACKEND.requests().getLast().query()).contains("type=kanban");
    }

    @Test
    void theBoardsArePagedWhenJiraSaysOnlyWhetherThePageIsTheLast() {
        try (McpSyncClient client = clientWithCredential(PAT)) {
            JsonNode first = json(call(client, "list_boards", Map.of("project", "DEF")));
            assertThat(first.at("/items")).hasSize(25);
            assertThat(first.at("/items/0/project").asString()).isEqualTo("DEF");
            String cursor = first.at("/nextCursor").asString();
            assertThat(cursor).isNotBlank();

            JsonNode second = json(call(client, "list_boards", Map.of("project", "DEF", "cursor", cursor)));
            assertThat(second.at("/items")).hasSize(5);
            assertThat(second.at("/items/0/id").asString()).isEqualTo("125");
            assertThat(second.at("/nextCursor").isNull() || second.at("/nextCursor").isMissingNode()).isTrue();

            assertInvalidArgument(call(client, "list_boards", Map.of("project", "ABC", "cursor", cursor)), "The cursor is not valid");
        }
    }

    @Test
    void theSprintsOfABoardCarryTheirDatesAndTheGoalAsUntrustedData() {
        try (McpSyncClient client = clientWithCredential(PAT)) {
            McpSchema.CallToolResult result = call(client, "list_sprints", Map.of("boardId", "7"));

            assertThat(result.isError()).isFalse();
            JsonNode page = json(result);
            assertThat(page.at("/items")).extracting(sprint -> sprint.get("id").asString()).containsExactly("70", "71", "72", "77");
            assertThat(page.at("/items/0/name/text").asString()).isEqualTo("Sprint 1");
            assertThat(page.at("/items/0/state").asString()).isEqualTo("active");
            assertThat(page.at("/items/0/startDate").asString()).isEqualTo("2026-10-26T08:00:00.000Z");
            assertThat(page.at("/items/0/goal/kind").asString()).isEqualTo("untrusted-data");
            assertThat(page.at("/items/0/goal/text").asString()).contains("Ignore previous instructions");
            assertThat(page.at("/items/1/goal").isNull() || page.at("/items/1/goal").isMissingNode()).isTrue();
            assertThat(page.at("/items/2/completeDate").asString()).isEqualTo("2026-10-26T09:15:00.000Z");
            assertThat(text(result)).doesNotContain("originBoardId", "self", "Payroll", "Orphan");
        }
        assertThat(BACKEND.requests()).extracting(StubBackend.Request::path)
                .as("the projects of the board are read and judged before the sprints are asked for")
                .containsSubsequence("/rest/agile/1.0/board/7/project", "/rest/api/2/project/ABC", "/rest/agile/1.0/board/7/sprint")
                .as("a sprint created on another board is judged by that board's projects")
                .contains("/rest/agile/1.0/board/9/project", "/rest/agile/1.0/board/8/project");
        assertThat(BACKEND.requests().stream().filter(r -> r.path().endsWith("/board/9/project"))).hasSize(1);
        assertThat(BACKEND.requests().stream().filter(r -> r.path().endsWith("/board/7/sprint")).findFirst().orElseThrow().query())
                .contains("startAt=0", "maxResults=25").doesNotContain("state=");
    }

    @Test
    void theSprintStateIsAFilter() {
        try (McpSyncClient client = clientWithCredential(PAT)) {
            JsonNode page = json(call(client, "list_sprints", Map.of("boardId", "7", "state", "closed")));
            assertThat(page.at("/items")).extracting(sprint -> sprint.get("id").asString()).containsExactly("72");

            assertInvalidArgument(call(client, "list_sprints", Map.of("boardId", "7", "state", "active,future")), "state does not match");
        }
        assertThat(BACKEND.requests().getLast().query()).contains("state=closed");
    }

    @Test
    void sprintsArePagedByCursor() {
        try (McpSyncClient client = clientWithCredential(PAT)) {
            JsonNode first = json(call(client, "list_sprints", Map.of("boardId", "13")));
            assertThat(first.at("/items")).hasSize(25);
            String cursor = first.at("/nextCursor").asString();

            JsonNode second = json(call(client, "list_sprints", Map.of("boardId", "13", "cursor", cursor)));
            assertThat(second.at("/items")).hasSize(5);
            assertThat(second.at("/items/0/id").asString()).isEqualTo("225");
            assertThat(second.at("/nextCursor").isNull() || second.at("/nextCursor").isMissingNode()).isTrue();

            assertInvalidArgument(call(client, "list_sprints", Map.of("boardId", "7", "cursor", cursor)), "The cursor is not valid");
        }
    }

    @Test
    void aBoardWithoutSprintsIsRefusedWithAFixedText() {
        try (McpSyncClient client = clientWithCredential(PAT)) {
            McpSchema.CallToolResult result = call(client, "list_sprints", Map.of("boardId", "8"));

            assertInvalidArgument(result, "the board may not support sprints");
            assertThat(text(result)).doesNotContain("does not support sprints\"");
        }
    }

    @Test
    void aBoardThatCoversAProjectTheCallerMayNotReadLooksLikeAMissingBoard() {
        try (McpSyncClient client = clientWithCredential(PAT)) {
            McpSchema.CallToolResult missing = call(client, "list_sprints", Map.of("boardId", "99"));
            assertNotFoundOrDenied(missing);
            for (String board : List.of("9", "10", "11", "12", "14", "15", "16")) {
                assertThat(text(call(client, "list_sprints", Map.of("boardId", board)))).as("board " + board).isEqualTo(text(missing));
            }
        }
        assertThat(BACKEND.requests()).as("no sprint of those boards was asked for")
                .extracting(StubBackend.Request::path).noneMatch(path -> path.endsWith("/sprint"));
    }

    @Test
    void theIssuesOfASprintAreJudgedOneByOne() {
        try (McpSyncClient client = clientWithCredential(PAT)) {
            McpSchema.CallToolResult result = call(client, "list_sprint_issues", Map.of("sprintId", "70"));

            assertThat(result.isError()).isFalse();
            JsonNode page = json(result);
            assertThat(page.at("/items")).extracting(issue -> issue.get("key").asString()).containsExactly("ABC-1", "ABC-2", "DEF-5");
            assertThat(page.at("/items/0/summary/kind").asString()).isEqualTo("untrusted-data");
            assertThat(page.at("/items/0/status").asString()).isEqualTo("To Do");
            assertThat(text(result)).as("HR-1 is in a restricted project").doesNotContain("HR-1", "Salary");
        }
        StubBackend.Request read = BACKEND.requests().stream().filter(r -> r.path().endsWith("/issue") && r.path().contains("/sprint/"))
                .findFirst().orElseThrow();
        assertThat(read.path()).isEqualTo("/rest/agile/1.0/sprint/70/issue");
        assertThat(read.query()).contains("startAt=0", "maxResults=25", "fields=summary%2Cstatus%2Cissuetype%2Cassignee%2Cproject%2Cupdated");
        assertThat(BACKEND.authorizations()).containsOnly(PAT);
    }

    @Test
    void theIssuesOfASprintArePagedByCursor() {
        try (McpSyncClient client = clientWithCredential(PAT)) {
            JsonNode first = json(call(client, "list_sprint_issues", Map.of("sprintId", "71")));
            assertThat(first.at("/items")).hasSize(25);
            String cursor = first.at("/nextCursor").asString();
            assertThat(cursor).isNotBlank();

            JsonNode second = json(call(client, "list_sprint_issues", Map.of("sprintId", "71", "cursor", cursor)));
            assertThat(second.at("/items")).hasSize(5);
            assertThat(second.at("/items/0/key").asString()).isEqualTo("ABC-125");
            assertThat(second.at("/nextCursor").isNull() || second.at("/nextCursor").isMissingNode()).isTrue();

            assertInvalidArgument(call(client, "list_sprint_issues", Map.of("sprintId", "72", "cursor", cursor)), "The cursor is not valid");
            assertThat(json(call(client, "list_sprint_issues", Map.of("sprintId", "72"))).at("/items")).isEmpty();
        }
    }

    @Test
    void aSprintOfARestrictedBoardOrWithoutABoardLooksLikeAMissingSprint() {
        try (McpSyncClient client = clientWithCredential(PAT)) {
            McpSchema.CallToolResult missing = call(client, "list_sprint_issues", Map.of("sprintId", "74"));
            assertNotFoundOrDenied(missing);
            for (String sprint : List.of("73", "75")) {
                assertThat(text(call(client, "list_sprint_issues", Map.of("sprintId", sprint)))).as("sprint " + sprint)
                        .isEqualTo(text(missing));
            }
        }
        assertThat(BACKEND.requests()).extracting(StubBackend.Request::path).noneMatch(path -> path.endsWith("/issue"));
    }

    @Test
    void theIdsAreDecimalNumbers() {
        try (McpSyncClient client = clientWithCredential(PAT)) {
            assertInvalidArgument(call(client, "list_sprints", Map.of("boardId", "7/../8")), "boardId does not match");
            assertInvalidArgument(call(client, "list_sprints", Map.of("boardId", "")), "boardId");
            assertInvalidArgument(call(client, "list_sprint_issues", Map.of("sprintId", "70?x=1")), "sprintId does not match");
            assertInvalidArgument(call(client, "list_sprint_issues", Map.of("sprintId", "1".repeat(19))), "sprintId is longer than 18");
        }
        assertThat(BACKEND.requests()).isEmpty();
    }
}
