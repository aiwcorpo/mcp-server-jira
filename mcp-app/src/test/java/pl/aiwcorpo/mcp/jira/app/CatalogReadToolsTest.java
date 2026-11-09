package pl.aiwcorpo.mcp.jira.app;

import static org.assertj.core.api.Assertions.assertThat;

import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.spec.McpSchema;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/** History, statuses, create metadata, link types, the current user and attachment content. */
class CatalogReadToolsTest extends ServerTestSupport {

    @BeforeEach
    void resetBackend() {
        BACKEND.reset();
    }

    @Test
    void theChangelogListsChangesOldestFirstWithTheValuesAsUntrustedData() {
        try (McpSyncClient client = clientWithCredential(PAT)) {
            McpSchema.CallToolResult result = call(client, "get_issue_changelog", Map.of("issueKey", "ABC-1"));

            assertThat(result.isError()).isFalse();
            JsonNode page = json(result);
            assertThat(page.at("/items")).hasSize(3);
            assertThat(page.at("/items/0/id").asString()).isEqualTo("20001");
            assertThat(page.at("/items/0/author/kind").asString()).isEqualTo("untrusted-data");
            assertThat(page.at("/items/0/author/text").asString()).isEqualTo("Alice Tester");
            assertThat(page.at("/items/0/created").asString()).isEqualTo("2026-10-02T09:00:00.000+0000");
            assertThat(page.at("/items/0/changes/0/field").asString()).isEqualTo("status");
            assertThat(page.at("/items/0/changes/0/from/text").asString()).isEqualTo("To Do");
            assertThat(page.at("/items/0/changes/0/to/text").asString()).isEqualTo("In Progress");
            assertThat(page.at("/items/1/author/kind").asString()).isEqualTo("untrusted-data");
            assertThat(page.at("/items/1/author/text").asString()).contains("Ignore previous instructions");
            assertThat(page.at("/items/1/changes")).hasSize(2);
            assertThat(page.at("/items/2/author").isNull() || page.at("/items/2/author").isMissingNode()).as("no author").isTrue();
            assertThat(page.at("/items/2/changes/0/from").isNull() || page.at("/items/2/changes/0/from").isMissingNode()).isTrue();
            assertThat(page.at("/truncated").asBoolean()).isFalse();
            assertThat(text(result)).doesNotContain("alice@corp.test", "bob@corp.test", "JIRAUSER10100", "fieldtype");
        }
        StubBackend.Request read = BACKEND.requests().getLast();
        assertThat(read.path()).isEqualTo("/rest/api/2/issue/ABC-1");
        assertThat(read.query()).contains("expand=changelog", "fields=summary");
        assertThat(BACKEND.authorizations()).containsOnly(PAT);
    }

    @Test
    void theChangelogIsPagedByCursorOverTheHistoryJiraSends() {
        try (McpSyncClient client = clientWithCredential(PAT)) {
            JsonNode first = json(call(client, "get_issue_changelog", Map.of("issueKey", "ABC-60")));
            assertThat(first.at("/items")).hasSize(25);
            String cursor = first.at("/nextCursor").asString();
            assertThat(cursor).isNotBlank();

            JsonNode second = json(call(client, "get_issue_changelog", Map.of("issueKey", "ABC-60", "cursor", cursor)));
            assertThat(second.at("/items")).hasSize(5);
            assertThat(second.at("/items/0/id").asString()).isEqualTo("40025");
            assertThat(second.at("/nextCursor").isNull() || second.at("/nextCursor").isMissingNode()).isTrue();

            assertInvalidArgument(call(client, "get_issue_changelog", Map.of("issueKey", "ABC-1", "cursor", cursor)),
                    "The cursor is not valid");
        }
    }

    @Test
    void keysOfUnreadableProjectsAreHiddenAndReferenceChangesWithoutProofAreLeftOut() {
        try (McpSyncClient client = clientWithCredential(PAT)) {
            McpSchema.CallToolResult result = call(client, "get_issue_changelog", Map.of("issueKey", "ABC-63"));

            JsonNode page = json(result);
            assertThat(page.at("/items")).extracting(item -> item.get("id").asString())
                    .as("a link with other text, to a restricted issue, to a moved issue and without a key leave no entry")
                    .containsExactly("50004", "50005", "50006");
            assertThat(page.at("/items/0/changes")).hasSize(1);
            assertThat(page.at("/items/0/changes/0/from/text").asString()).isEqualTo("see <hidden-issue> and ABC-2");
            assertThat(page.at("/items/0/changes/0/to/text").asString()).isEqualTo("see <hidden-issue>");
            assertThat(page.at("/items/1/changes/0/field").asString()).isEqualTo("Parent");
            assertThat(page.at("/items/1/changes/0/from/text").asString()).as("a key of a readable project alone").isEqualTo("ABC-404");
            assertThat(page.at("/items/1/changes/0/to/text").asString()).isEqualTo("ABC-2");
            assertThat(page.at("/items/2/changes/0/field").asString()).isEqualTo("Root cause");
            assertThat(page.at("/items/2/changes/0/to/text").asString()).isEqualTo("Root cause: <hidden-issue> and ABC-2");
            assertThat(page.toString()).doesNotContain("redacted", "HR-1", "HR-2", "SEC-12", "SEC-5", "ABC-9", "Human Resources",
                    "Salary", "epic without");
        }
        assertThat(BACKEND.requests()).as("keys are judged by their project, never by resolving the issue")
                .extracting(StubBackend.Request::path).doesNotContain("/rest/api/2/issue/SEC-5", "/rest/api/2/issue/HR-1");
    }

    @Test
    void theEntryOfTheLastProjectChangeAndEveryOlderOneAreNotReturned() {
        try (McpSyncClient client = clientWithCredential(PAT)) {
            McpSchema.CallToolResult result = call(client, "get_issue_changelog", Map.of("issueKey", "ABC-64"));

            JsonNode page = json(result);
            assertThat(page.at("/items")).extracting(item -> item.get("id").asString()).containsExactly("60003", "60004");
            assertThat(page.at("/items/0/changes/0/to/text").asString()).isEqualTo("Backend");
            assertThat(page.at("/omitted")).as("the history is complete").isEmpty();
            assertThat(text(result)).doesNotContain("HR only", "HR approval", "Human Resources", "Security Level");
        }
    }

    @Test
    void anIncompleteHistoryShowsOnlyFieldsThatDoNotDependOnTheProject() {
        try (McpSyncClient client = clientWithCredential(PAT)) {
            for (String key : List.of("ABC-66", "ABC-67")) {
                McpSchema.CallToolResult result = call(client, "get_issue_changelog", Map.of("issueKey", key));

                JsonNode page = json(result);
                assertThat(page.at("/omitted/0").asString()).as(key).isEqualTo("history_incomplete");
                assertThat(page.at("/items")).as(key + ": the entry with a Component/s change only is gone").hasSize(1);
                assertThat(page.at("/items/0/changes")).extracting(change -> change.get("field").asString())
                        .containsExactly("summary", "Parent");
                assertThat(text(result)).as(key).doesNotContain("HR only", "Security Level", "Backend");
            }
        }
    }

    @Test
    void anIssueShowsItsLinksToReadableIssuesOnlyAndNeverCountsTheOthers() {
        try (McpSyncClient client = clientWithCredential(PAT)) {
            McpSchema.CallToolResult result = call(client, "get_issue", Map.of("issueKey", "ABC-1"));

            JsonNode issue = json(result);
            assertThat(issue.at("/links")).hasSize(1);
            assertThat(issue.at("/links/0/id").asString()).isEqualTo("90001");
            assertThat(issue.at("/links/0/type").asString()).isEqualTo("Blocks");
            assertThat(issue.at("/links/0/direction").asString()).isEqualTo("inward");
            assertThat(issue.at("/links/0/description").asString()).isEqualTo("is blocked by");
            assertThat(issue.at("/links/0/issueKey").asString()).isEqualTo("ABC-2");
            assertThat(issue.at("/linksTruncated").asBoolean()).isFalse();
            assertThat(text(result)).doesNotContain("HR-1", "Salary", "90000", "Second issue");
        }
        assertThat(BACKEND.requests().getFirst().query()).contains("issuelinks");
    }

    @Test
    void theLinksOfAnIssueAreLimitedAfterTheRestrictedOnesAreDropped() {
        try (McpSyncClient client = clientWithCredential(PAT)) {
            JsonNode issue = json(call(client, "get_issue", Map.of("issueKey", "ABC-62")));

            assertThat(issue.at("/links")).hasSize(50);
            assertThat(issue.at("/links/0/id").asString()).as("the restricted link in front is gone").isEqualTo("91001");
            assertThat(issue.at("/links/0/direction").asString()).isEqualTo("outward");
            assertThat(issue.at("/linksTruncated").asBoolean()).isTrue();
            assertThat(issue.toString()).doesNotContain("HR-1");
        }
    }

    @Test
    void onlyTheFirstTwoHundredLinksAreJudgedAndTheResultSaysSo() {
        try (McpSyncClient client = clientWithCredential(PAT)) {
            JsonNode issue = json(call(client, "get_issue", Map.of("issueKey", "ABC-65")));

            assertThat(issue.at("/links")).hasSize(50);
            assertThat(issue.at("/linksTruncated").asBoolean()).isTrue();
        }
        assertThat(BACKEND.requests().stream().map(StubBackend.Request::path).filter(path -> path.startsWith("/rest/api/2/issue/ABC-1")))
                .as("at most 200 linked issues were resolved, 60 distinct ones, each once").hasSizeLessThanOrEqualTo(61);
    }

    @Test
    void anUnrelatedChangeIsNotRedacted() {
        try (McpSyncClient client = clientWithCredential(PAT)) {
            JsonNode page = json(call(client, "get_issue_changelog", Map.of("issueKey", "ABC-1")));

            assertThat(page.at("/items")).hasSize(3);
            assertThat(page.at("/omitted")).isEmpty();
            assertThat(page.at("/items/0/changes/0/to/text").asString()).isEqualTo("In Progress");
        }
    }

    @Test
    void longValuesAndLongChangesAreCutAndTheResultSaysSo() {
        try (McpSyncClient client = clientWithCredential(PAT)) {
            JsonNode page = json(call(client, "get_issue_changelog", Map.of("issueKey", "ABC-61")));

            assertThat(page.at("/items/0/changes")).hasSize(50);
            assertThat(page.at("/items/0/changes/0/to/truncated").asBoolean()).isTrue();
            assertThat(page.at("/items/0/changes/0/to/text").asString()).hasSizeLessThanOrEqualTo(500);
            assertThat(page.at("/truncated").asBoolean()).isTrue();
        }
    }

    @Test
    void theStatusesOfAProjectComePerIssueType() {
        try (McpSyncClient client = clientWithCredential(PAT)) {
            McpSchema.CallToolResult result = call(client, "list_statuses", Map.of("project", "ABC"));

            JsonNode view = json(result);
            assertThat(view.at("/project").asString()).isEqualTo("ABC");
            assertThat(view.at("/issueTypes")).hasSize(2);
            assertThat(view.at("/issueTypes/0/issueType").asString()).isEqualTo("Task");
            assertThat(view.at("/issueTypes/0/statuses")).extracting(status -> status.get("name").asString())
                    .containsExactly("To Do", "In Progress", "Done");
            assertThat(view.at("/issueTypes/0/statuses/1/category").asString()).isEqualTo("In Progress");
            assertThat(view.at("/issueTypes/1/statuses/0/category").isNull()
                    || view.at("/issueTypes/1/statuses/0/category").isMissingNode()).isTrue();
            assertThat(view.at("/truncated").asBoolean()).isFalse();
            assertThat(text(result)).doesNotContain("statusCategory", "description");
        }
        assertThat(BACKEND.requests()).extracting(StubBackend.Request::path)
                .containsExactly("/rest/api/2/project/ABC", "/rest/api/2/project/ABC/statuses");
    }

    @Test
    void aLongStatusListIsCutAndSaysSo() {
        try (McpSyncClient client = clientWithCredential(PAT)) {
            JsonNode view = json(call(client, "list_statuses", Map.of("project", "DEF")));

            assertThat(view.at("/issueTypes")).hasSize(50);
            assertThat(view.at("/truncated").asBoolean()).isTrue();
        }
    }

    @Test
    void theIssueTypesOfAProjectAreListedWithTheirIds() {
        try (McpSyncClient client = clientWithCredential(PAT)) {
            JsonNode page = json(call(client, "list_issue_types", Map.of("project", "ABC")));

            assertThat(page.at("/items")).extracting(type -> type.get("name").asString()).containsExactly("Task", "Bug", "Sub-task");
            assertThat(page.at("/items/0/id").asString()).isEqualTo("10002");
            assertThat(page.at("/items/0/subtask").asBoolean()).isFalse();
            assertThat(page.at("/items/2/subtask").asBoolean()).isTrue();
            assertThat(page.at("/nextCursor").isNull() || page.at("/nextCursor").isMissingNode()).isTrue();
            assertThat(page.toString()).doesNotContain("iconUrl", "description");
        }
        StubBackend.Request read = BACKEND.requests().getLast();
        assertThat(read.path()).isEqualTo("/rest/api/2/issue/createmeta/ABC/issuetypes");
        assertThat(read.query()).contains("startAt=0", "maxResults=25");
    }

    @Test
    void theIssueTypesArePagedEvenWhenJiraSaysOnlyWhetherThePageIsTheLast() {
        try (McpSyncClient client = clientWithCredential(PAT)) {
            JsonNode first = json(call(client, "list_issue_types", Map.of("project", "DEF")));
            assertThat(first.at("/items")).hasSize(25);
            String cursor = first.at("/nextCursor").asString();
            assertThat(cursor).isNotBlank();

            JsonNode second = json(call(client, "list_issue_types", Map.of("project", "DEF", "cursor", cursor)));
            assertThat(second.at("/items")).hasSize(5);
            assertThat(second.at("/items/0/id").asString()).isEqualTo("20026");
            assertThat(second.at("/nextCursor").isNull() || second.at("/nextCursor").isMissingNode()).isTrue();

            assertInvalidArgument(call(client, "list_issue_types", Map.of("project", "ABC", "cursor", cursor)),
                    "The cursor is not valid");
        }
    }

    @Test
    void theCreateFieldsSayWhichAreRequiredAndWhichValuesAreAllowed() {
        try (McpSyncClient client = clientWithCredential(PAT)) {
            McpSchema.CallToolResult result = call(client, "get_create_fields",
                    Map.of("project", "ABC", "issueTypeId", "10002"));

            JsonNode view = json(result);
            assertThat(view.at("/project").asString()).isEqualTo("ABC");
            assertThat(view.at("/issueTypeId").asString()).isEqualTo("10002");
            assertThat(view.at("/fields")).extracting(field -> field.get("fieldId").asString())
                    .containsExactly("summary", "description", "priority", "components", "customfield_10010", "assignee");
            assertThat(view.at("/fields/0/required").asBoolean()).isTrue();
            assertThat(view.at("/fields/0/name").asString()).isEqualTo("Summary");
            assertThat(view.at("/fields/0/type").asString()).isEqualTo("string");
            assertThat(view.at("/fields/1/required").asBoolean()).isFalse();
            assertThat(view.at("/fields/2/allowedValues")).extracting(value -> value.get("name").asString())
                    .containsExactly("High", "Medium");
            assertThat(view.at("/fields/2/allowedValues/0/id").asString()).isEqualTo("2");
            assertThat(view.at("/fields/3/type").asString()).isEqualTo("array");
            assertThat(view.at("/fields/3/itemsType").asString()).isEqualTo("component");
            assertThat(view.at("/fields/4/allowedValues")).as("the option values come as value, not name").hasSize(20);
            assertThat(view.at("/fields/4/allowedValues/0/name").asString()).isEqualTo("Option 0");
            assertThat(view.at("/fields/4/allowedValuesTruncated").asBoolean()).isTrue();
            assertThat(view.at("/fields/0/allowedValuesTruncated").asBoolean()).isFalse();
            assertThat(view.at("/truncated").asBoolean()).as("a field had more allowed values").isTrue();
            assertThat(text(result)).doesNotContain("iconUrl", "hasDefaultValue", "operations", "self");
        }
        StubBackend.Request read = BACKEND.requests().getLast();
        assertThat(read.path()).isEqualTo("/rest/api/2/issue/createmeta/ABC/issuetypes/10002");
        assertThat(read.query()).contains("startAt=0", "maxResults=100");
    }

    @Test
    void moreFieldsThanAreReadAreReported() {
        try (McpSyncClient client = clientWithCredential(PAT)) {
            JsonNode view = json(call(client, "get_create_fields", Map.of("project", "DEF", "issueTypeId", "20002")));

            assertThat(view.at("/fields")).hasSize(100);
            assertThat(view.at("/truncated").asBoolean()).isTrue();
        }
    }

    @Test
    void anIssueTypeJiraDoesNotKnowOrRefusesLooksLikeAMissingOne() {
        try (McpSyncClient client = clientWithCredential(PAT)) {
            McpSchema.CallToolResult unknown = call(client, "get_create_fields", Map.of("project", "ABC", "issueTypeId", "99999"));
            McpSchema.CallToolResult refused = call(client, "get_create_fields", Map.of("project", "DEF", "issueTypeId", "20001"));
            McpSchema.CallToolResult restricted = call(client, "get_create_fields", Map.of("project", "HR", "issueTypeId", "10002"));

            assertNotFoundOrDenied(unknown);
            assertThat(text(refused)).isEqualTo(text(unknown)).doesNotContain("not valid");
            assertThat(text(restricted)).isEqualTo(text(unknown));
        }
    }

    @Test
    void theProjectToolsAnswerARestrictedMissingAndForbiddenProjectTheSame() {
        try (McpSyncClient client = clientWithCredential(PAT)) {
            for (Map.Entry<String, Map<String, Object>> call : Map.of(
                    "list_statuses", Map.<String, Object>of(),
                    "list_issue_types", Map.<String, Object>of(),
                    "get_create_fields", Map.<String, Object>of("issueTypeId", "10002"),
                    "list_boards", Map.<String, Object>of()).entrySet()) {
                McpSchema.CallToolResult missing = call(client, call.getKey(), with(call.getValue(), "project", "NOPE"));
                McpSchema.CallToolResult restricted = call(client, call.getKey(), with(call.getValue(), "project", "HR"));
                McpSchema.CallToolResult forbidden = call(client, call.getKey(), with(call.getValue(), "project", "SEC"));

                assertNotFoundOrDenied(missing);
                assertThat(text(restricted)).as(call.getKey()).isEqualTo(text(missing));
                assertThat(text(forbidden)).as(call.getKey()).isEqualTo(text(missing));
            }
        }
        assertThat(BACKEND.requests()).extracting(StubBackend.Request::path)
                .noneMatch(path -> path.contains("/HR/") || path.contains("/rest/agile/"));
    }

    @Test
    void linkTypesAreListedWithBothSentences() {
        try (McpSyncClient client = clientWithCredential(PAT)) {
            McpSchema.CallToolResult result = call(client, "list_link_types", Map.of());

            JsonNode view = json(result);
            assertThat(view.at("/linkTypes")).hasSize(2);
            assertThat(view.at("/linkTypes/0/id").asString()).isEqualTo("10000");
            assertThat(view.at("/linkTypes/0/name").asString()).isEqualTo("Blocks");
            assertThat(view.at("/linkTypes/0/inward").asString()).isEqualTo("is blocked by");
            assertThat(view.at("/linkTypes/0/outward").asString()).isEqualTo("blocks");
            assertThat(view.at("/truncated").asBoolean()).isFalse();
            assertThat(text(result)).doesNotContain("self");
        }
        assertThat(BACKEND.requests()).extracting(StubBackend.Request::path).containsExactly("/rest/api/2/issueLinkType");
    }

    @Test
    void aLongLinkTypeListIsCutAndSaysSo() {
        BACKEND.linkTypesListed(60);
        try {
            try (McpSyncClient client = clientWithCredential(PAT)) {
                JsonNode view = json(call(client, "list_link_types", Map.of()));

                assertThat(view.at("/linkTypes")).hasSize(50);
                assertThat(view.at("/truncated").asBoolean()).isTrue();
            }
        } finally {
            BACKEND.useDefaultFixtures();
        }
    }

    @Test
    void theCurrentUserHasNoEmailAddress() {
        try (McpSyncClient client = clientWithCredential(PAT)) {
            McpSchema.CallToolResult result = call(client, "get_current_user", Map.of());

            JsonNode user = json(result);
            assertThat(user.at("/username").asString()).isEqualTo("jkowalski");
            assertThat(user.at("/key").asString()).isEqualTo("JIRAUSER10000");
            assertThat(user.at("/displayName/kind").asString()).isEqualTo("untrusted-data");
            assertThat(user.at("/displayName/text").asString()).isEqualTo("Jan Kowalski");
            assertThat(user.at("/active").asBoolean()).isTrue();
            assertThat(text(result)).doesNotContain("jkowalski@corp.test", "timeZone", "groups", "locale");
        }
        assertThat(BACKEND.requests()).extracting(StubBackend.Request::path).containsExactly("/rest/api/2/myself");
        assertThat(BACKEND.authorizations()).containsOnly(PAT);
    }

    @Test
    void aTextAttachmentIsReadFromTheConfiguredJiraNotFromTheAddressInTheAnswer() {
        try (McpSyncClient client = clientWithCredential(PAT)) {
            McpSchema.CallToolResult result = call(client, "read_attachment", Map.of("issueKey", "ABC-45", "attachmentId", "30001"));

            assertThat(result.isError()).isFalse();
            JsonNode view = json(result);
            assertThat(view.at("/issueKey").asString()).isEqualTo("ABC-45");
            assertThat(view.at("/id").asString()).isEqualTo("30001");
            assertThat(view.at("/filename/text").asString()).isEqualTo("notes.txt");
            assertThat(view.at("/mimeType").asString()).isEqualTo("text/plain;charset=UTF-8");
            assertThat(view.at("/content/kind").asString()).isEqualTo("untrusted-data");
            assertThat(view.at("/content/text").asString()).isEqualTo("Deploy at 10:00\n");
            assertThat(view.at("/content/truncated").asBoolean()).isFalse();
            assertThat(view.at("/omitted")).isEmpty();
        }
        StubBackend.Request download = BACKEND.requests().getLast();
        assertThat(download.method()).isEqualTo("GET");
        assertThat(download.path()).isEqualTo("/secure/attachment/30001/notes.txt");
        assertThat(download.authorization()).isEqualTo(PAT);
        assertThat(BACKEND.elsewhereHits()).isEmpty();
    }

    @Test
    void aJsonAttachmentIsText() {
        try (McpSyncClient client = clientWithCredential(PAT)) {
            JsonNode view = json(call(client, "read_attachment", Map.of("issueKey", "ABC-45", "attachmentId", "30006")));

            assertThat(view.at("/omitted")).isEmpty();
            assertThat(view.at("/content/text").asString()).contains("\"a\"");
        }
    }

    @Test
    void aBinaryAndAnOversizedAttachmentAreNotDownloaded() {
        try (McpSyncClient client = clientWithCredential(PAT)) {
            JsonNode image = json(call(client, "read_attachment", Map.of("issueKey", "ABC-45", "attachmentId", "30002")));
            assertThat(image.at("/omitted/0").asString()).isEqualTo("binary_attachment");
            assertThat(image.at("/content/text").asString()).isEmpty();

            JsonNode big = json(call(client, "read_attachment", Map.of("issueKey", "ABC-45", "attachmentId", "30003")));
            assertThat(big.at("/omitted/0").asString()).isEqualTo("attachment_too_large");
            assertThat(big.at("/content/text").asString()).isEmpty();
        }
        assertThat(BACKEND.requests()).extracting(StubBackend.Request::path).noneMatch(path -> path.startsWith("/secure/"));
    }

    @Test
    void aFileDeclaredAsTextThatIsNotUtf8TextIsBinary() {
        try (McpSyncClient client = clientWithCredential(PAT)) {
            for (String id : List.of("30008", "30009")) {
                JsonNode view = json(call(client, "read_attachment", Map.of("issueKey", "ABC-45", "attachmentId", id)));

                assertThat(view.at("/omitted/0").asString()).as(id).isEqualTo("binary_attachment");
                assertThat(view.at("/content/text").asString()).as(id).isEmpty();
            }
        }
    }

    @Test
    void aLongTextIsCutAtFiftyThousandCharacters() {
        try (McpSyncClient client = clientWithCredential(PAT)) {
            JsonNode view = json(call(client, "read_attachment", Map.of("issueKey", "ABC-45", "attachmentId", "30004")));

            assertThat(view.at("/content/text").asString()).hasSize(50_000);
            assertThat(view.at("/content/truncated").asBoolean()).isTrue();
            assertThat(view.at("/omitted")).isEmpty();
        }
        assertThat(BACKEND.requests().getLast().path()).as("the stub decodes the path: a space in the name arrived as one segment")
                .isEqualTo("/secure/attachment/30004/long log.txt");
    }

    @Test
    void anAttachmentOfAnotherIssueOrOfAHiddenIssueLooksLikeAMissingOne() {
        try (McpSyncClient client = clientWithCredential(PAT)) {
            McpSchema.CallToolResult otherIssue = call(client, "read_attachment", Map.of("issueKey", "ABC-45", "attachmentId", "10800"));
            McpSchema.CallToolResult missing = call(client, "read_attachment", Map.of("issueKey", "ABC-45", "attachmentId", "99999"));
            McpSchema.CallToolResult gone = call(client, "read_attachment", Map.of("issueKey", "ABC-45", "attachmentId", "30007"));
            McpSchema.CallToolResult restricted = call(client, "read_attachment", Map.of("issueKey", "HR-1", "attachmentId", "30001"));
            McpSchema.CallToolResult noIssue = call(client, "read_attachment", Map.of("issueKey", "ABC-404", "attachmentId", "30001"));

            assertNotFoundOrDenied(otherIssue);
            for (McpSchema.CallToolResult other : List.of(missing, gone, restricted, noIssue)) {
                assertThat(text(other)).isEqualTo(text(otherIssue));
            }
        }
        assertThat(BACKEND.requests()).extracting(StubBackend.Request::path).doesNotContain("/secure/attachment/10800/stacktrace.txt");
    }

    @Test
    void aDownloadThatRedirectsIsNotFollowed() {
        try (McpSyncClient client = clientWithCredential(PAT)) {
            McpSchema.CallToolResult result = call(client, "read_attachment", Map.of("issueKey", "ABC-45", "attachmentId", "30005"));

            assertThat(result.isError()).isTrue();
            assertThat(text(result)).contains("\"error\":\"internal_error\"");
        }
        assertThat(BACKEND.elsewhereHits()).isEmpty();
    }

    @Test
    void theAttachmentIdIsADecimalNumber() {
        try (McpSyncClient client = clientWithCredential(PAT)) {
            assertInvalidArgument(call(client, "read_attachment", Map.of("issueKey", "ABC-45", "attachmentId", "../30001")),
                    "attachmentId does not match");
            assertInvalidArgument(call(client, "read_attachment", Map.of("issueKey", "ABC-45", "attachmentId", "")),
                    "attachmentId");
            assertInvalidArgument(call(client, "get_create_fields", Map.of("project", "ABC", "issueTypeId", "10002/../x")),
                    "issueTypeId does not match");
        }
        assertThat(BACKEND.requests()).isEmpty();
    }

    @Test
    void everyNewIssueToolAnswersAMissingAForbiddenAndARestrictedIssueTheSame() {
        try (McpSyncClient client = clientWithCredential(PAT)) {
            McpSchema.CallToolResult missing = call(client, "get_issue_changelog", Map.of("issueKey", "ABC-404"));
            assertNotFoundOrDenied(missing);
            assertThat(text(call(client, "get_issue_changelog", Map.of("issueKey", "ABC-403")))).isEqualTo(text(missing));
            assertThat(text(call(client, "get_issue_changelog", Map.of("issueKey", "HR-1")))).isEqualTo(text(missing));
            assertThat(text(call(client, "get_issue_changelog", Map.of("issueKey", "ABC-30")))).as("moved to the restricted project")
                    .isEqualTo(text(missing));
        }
    }

    @Test
    void everyNewReadToolAsksToConnectTheAccountWithoutACredentialOrWithARevokedOne() {
        Map<String, Map<String, Object>> calls = new java.util.LinkedHashMap<>();
        calls.put("get_issue_changelog", Map.of("issueKey", "ABC-1"));
        calls.put("list_statuses", Map.of("project", "ABC"));
        calls.put("list_issue_types", Map.of("project", "ABC"));
        calls.put("get_create_fields", Map.of("project", "ABC", "issueTypeId", "10002"));
        calls.put("list_link_types", Map.of());
        calls.put("get_current_user", Map.of());
        calls.put("read_attachment", Map.of("issueKey", "ABC-45", "attachmentId", "30001"));
        calls.put("list_boards", Map.of("project", "ABC"));
        calls.put("list_sprints", Map.of("boardId", "7"));
        calls.put("list_sprint_issues", Map.of("sprintId", "70"));
        try (McpSyncClient none = clientWithCredential(null);
                McpSyncClient revoked = clientWithCredential(StubBackend.EXPIRED_CREDENTIAL)) {
            calls.forEach((tool, arguments) -> {
                assertCredentialRequired(call(none, tool, arguments));
                assertCredentialRequired(call(revoked, tool, arguments));
            });
        }
    }

    @Test
    void aReaderWithoutAProjectGrantReadsTheNewTools() {
        try (McpSyncClient client = clientWithToken(ServerTestSupport::reader, PAT)) {
            assertThat(call(client, "get_issue_changelog", Map.of("issueKey", "ABC-1")).isError()).isFalse();
            assertThat(call(client, "list_statuses", Map.of("project", "ABC")).isError()).isFalse();
            assertThat(call(client, "get_current_user", Map.of()).isError()).isFalse();
            assertThat(call(client, "list_link_types", Map.of()).isError()).isFalse();
        }
    }

    @Test
    void theNewReadsWorkOnTheResponsesOfBothVersions() {
        try {
            for (String version : List.of("9.12", "11.3")) {
                BACKEND.useFixtures(version);
                try (McpSyncClient client = clientWithCredential(PAT)) {
                    assertThat(json(call(client, "get_issue_changelog", Map.of("issueKey", "ABC-1"))).at("/items")).as(version).hasSize(3);
                    assertThat(json(call(client, "list_issue_types", Map.of("project", "ABC"))).at("/items")).as(version).hasSize(3);
                    assertThat(json(call(client, "get_create_fields", Map.of("project", "ABC", "issueTypeId", "10002")))
                            .at("/fields")).as(version).hasSize(6);
                    assertThat(json(call(client, "list_link_types", Map.of())).at("/linkTypes")).as(version).hasSize(2);
                    assertThat(json(call(client, "get_current_user", Map.of())).at("/username").asString()).as(version)
                            .isEqualTo("jkowalski");
                }
            }
        } finally {
            BACKEND.useDefaultFixtures();
        }
    }

    private static Map<String, Object> with(Map<String, Object> template, String name, String value) {
        Map<String, Object> arguments = new java.util.HashMap<>(template);
        arguments.put(name, value);
        return arguments;
    }
}
