package pl.aiwcorpo.mcp.jira.app;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import pl.aiwcorpo.mcp.manifest.DefinitionHash;
import pl.aiwcorpo.mcp.manifest.ManifestRules;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The C6 manifest at {@code GET /mcp-manifest}: it passes the schema and rules the gateway applies at registration, and
 * its tool definitions are exactly what {@code tools/list} publishes, so the {@code definitionHash} the gateway pins is
 * the one of the tools clients see. The starter checks the same at startup outside dev; this keeps it from being
 * switched off unnoticed.
 */
class ManifestTest extends ServerTestSupport {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(java.time.Duration.ofSeconds(5)).build();

    private HttpResponse<String> get(String path, Map<String, String> headers) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(baseUrl() + path)).GET();
        headers.forEach(request::header);
        return http.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> post(String body, Map<String, String> headers, String session) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(baseUrl() + ENDPOINT))
                .header("Content-Type", "application/json")
                .header("Accept", "application/json, text/event-stream")
                .POST(HttpRequest.BodyPublishers.ofString(body));
        headers.forEach(request::header);
        if (session != null) {
            request.header("Mcp-Session-Id", session);
        }
        return http.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    private JsonNode manifest() throws Exception {
        HttpResponse<String> response = get("/mcp-manifest", GATEWAY.service().mint().headers());
        assertThat(response.statusCode()).isEqualTo(200);
        return JSON.readTree(response.body());
    }

    /** The raw {@code tools/list} result, as a client receives it. */
    private List<JsonNode> listedTools() throws Exception {
        HttpResponse<String> initialize = post("""
                {"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-06-18",
                "capabilities":{},"clientInfo":{"name":"manifest-test","version":"1"}}}""",
                GATEWAY.user().mint().headers(), null);
        assertThat(initialize.statusCode()).isEqualTo(200);
        String session = initialize.headers().firstValue("Mcp-Session-Id").orElseThrow();
        post("{\"jsonrpc\":\"2.0\",\"method\":\"notifications/initialized\"}", GATEWAY.user().mint().headers(), session);
        String body = post("{\"jsonrpc\":\"2.0\",\"id\":3,\"method\":\"tools/list\"}", GATEWAY.user().mint().headers(), session).body();
        int data = body.indexOf("data:");
        JsonNode message = JSON.readTree(data >= 0 ? body.substring(data + 5).strip() : body);
        List<JsonNode> tools = new ArrayList<>();
        message.at("/result/tools").forEach(tools::add);
        return tools;
    }

    @Test
    void outsideTheDevstackProfileThereIsNoEnvironmentAndTheDefaultCredentialTypeComesFirst() throws Exception {
        JsonNode manifest = manifest();

        assertThat(manifest.has("environment")).as("only the devstack profile publishes environment=dev (CC-138)").isFalse();
        assertThat(manifest.at("/credential/acceptedTypes").toString()).isEqualTo("[\"user-secret\"]");
    }

    @Test
    void theManifestPassesTheRulesOfTheGateway() throws Exception {
        JsonNode manifest = manifest();

        assertThat(ManifestRules.violations(manifest.toString())).isEmpty();
        assertThat(manifest.get("serverId").asString()).isEqualTo(SERVER_ID);
        assertThat(manifest.get("system").asString()).isEqualTo(SYSTEM);
    }

    @Test
    void theDefinitionsAreExactlyWhatToolsListPublishesSoTheHashesAgree() throws Exception {
        JsonNode manifest = manifest();
        List<JsonNode> listed = listedTools();

        assertThat(listed).hasSameSizeAs(manifest.get("tools"));
        for (JsonNode tool : manifest.get("tools")) {
            String name = tool.get("name").asString();
            JsonNode published = listed.stream().filter(t -> t.get("name").asString().equals(name)).findFirst().orElseThrow();
            assertThat(tool.get("definition")).as(name).isEqualTo(published);
            assertThat(DefinitionHash.ofManifestTool(manifest.toString(), name)).as(name)
                    .isEqualTo(tool.get("definitionHash").asString());
        }
    }

    @Test
    void everyToolHasATitleAndAClosedInputSchema() throws Exception {
        for (JsonNode tool : manifest().get("tools")) {
            assertThat(tool.at("/definition/title").asString()).isNotBlank();
            assertThat(tool.at("/definition/inputSchema/additionalProperties").asBoolean(true)).isFalse();
        }
    }

    @Test
    void theRiskAndTheResourceRefsOfEveryToolAreDeclared() throws Exception {
        Map<String, String> refArguments = new java.util.HashMap<>();
        for (String tool : List.of("get_issue", "list_comments", "list_transitions", "list_worklogs", "list_attachments",
                "add_comment", "edit_comment", "transition_issue", "update_issue", "assign_issue", "unassign_issue",
                "add_worklog", "delete_issue")) {
            refArguments.put(tool, "issueKey");
        }
        for (String tool : List.of("search_issues", "get_project", "find_assignable_users", "create_issue")) {
            refArguments.put(tool, "project");
        }
        JsonNode manifest = manifest();

        assertThat(manifest.get("tools")).hasSize(ToolListTest.READ_TOOLS_AND_WRITE_TOOLS.size());
        for (JsonNode tool : manifest.get("tools")) {
            String name = tool.get("name").asString();
            String risk = ToolListTest.READ_TOOLS.contains(name) ? "READ_ONLY"
                    : ToolListTest.UPDATE_TOOLS.contains(name) ? "UPDATE" : "DESTRUCTIVE";
            assertThat(tool.get("risk").asString()).as(name).isEqualTo(risk);
            if ("list_projects".equals(name)) {
                assertThat(tool.get("resourceRefs")).as("the projects are judged one by one in the result").isEmpty();
            } else if ("link_issues".equals(name)) {
                assertThat(tool.get("resourceRefs")).as(name).hasSize(2);
                assertThat(List.of(tool.at("/resourceRefs/0/argument").asString(), tool.at("/resourceRefs/1/argument").asString()))
                        .containsExactlyInAnyOrder("outwardIssueKey", "inwardIssueKey");
            } else {
                assertThat(tool.get("resourceRefs")).as(name).hasSize(1);
                assertThat(tool.at("/resourceRefs/0/argument").asString()).as(name).isEqualTo(refArguments.get(name));
            }
        }
    }

    @Test
    void createIssueNeedsTheUpdateActionOnAProject() throws Exception {
        for (JsonNode tool : manifest().get("tools")) {
            if ("create_issue".equals(tool.get("name").asString())) {
                assertThat(tool.at("/resourceRefs/0/type").asString()).isEqualTo("Jira::Project");
                assertThat(tool.get("action").asString()).isEqualTo("update");
            }
        }
    }

    @Test
    void deleteIssueIsDestructiveWithTheDeleteActionOnAnIssue() throws Exception {
        JsonNode tool = null;
        for (JsonNode candidate : manifest().get("tools")) {
            if (candidate.get("name").asString().equals("delete_issue")) {
                tool = candidate;
            }
        }

        assertThat(tool).isNotNull();
        assertThat(tool.get("risk").asString()).isEqualTo("DESTRUCTIVE");
        assertThat(tool.at("/resourceRefs/0/type").asString()).isEqualTo("Jira::Issue");
        assertThat(tool.at("/definition/annotations/destructiveHint").asBoolean()).isTrue();
        assertThat(tool.at("/definition/title").asString()).isEqualTo("Delete issue");
    }

    @Test
    void theManifestNeedsTheInternalJwtAndIsNotATool() throws Exception {
        assertThat(get("/mcp-manifest", Map.of()).statusCode()).isEqualTo(401);
        assertThat(get("/mcp-manifest", GATEWAY.user().tool("get_issue").mint().headers()).statusCode()).isEqualTo(401);
    }
}
