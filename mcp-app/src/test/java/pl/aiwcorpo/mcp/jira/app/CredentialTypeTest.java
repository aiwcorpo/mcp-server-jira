package pl.aiwcorpo.mcp.jira.app;

import static org.assertj.core.api.Assertions.assertThat;

import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport;
import io.modelcontextprotocol.spec.McpSchema;
import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import pl.aiwcorpo.mcp.platform.security.BackendCredential;

/**
 * A call that carries a credential must declare a type from the set of this server, which is {@code user-secret}
 * alone (CC-166): any other type is refused before the policy, the resolvers and Jira, and the credential is never sent.
 */
class CredentialTypeTest extends ServerTestSupport {

    @BeforeEach
    void resetBackend() {
        BACKEND.reset();
    }

    @Test
    void aCredentialOfAnotherTypeIsRefusedBeforeAnythingReachesJira() {
        for (BackendCredential.Type type : new BackendCredential.Type[] {BackendCredential.Type.USER_OAUTH,
                BackendCredential.Type.TOKEN_EXCHANGE, BackendCredential.Type.SERVICE_ACCOUNT}) {
            try (McpSyncClient client = clientWithType(type)) {
                McpSchema.CallToolResult result = call(client, "get_issue", Map.of("issueKey", "ABC-1"));

                assertThat(result.isError()).as(type.wireName()).isTrue();
                assertThat(text(result)).as(type.wireName()).contains("\"error\":\"internal_error\"")
                        .doesNotContain("ABC-1\"", PAT);
            }
        }
        assertThat(BACKEND.requests()).as("no resolver, no search, no credential on the wire").isEmpty();
        assertThat(BACKEND.authorizations()).isEmpty();
    }

    @Test
    void theTypeOfTheSystemIsAccepted() {
        try (McpSyncClient client = clientWithType(BackendCredential.Type.USER_SECRET)) {
            assertThat(call(client, "get_issue", Map.of("issueKey", "ABC-1")).isError()).isFalse();
        }
        assertThat(BACKEND.authorizations()).containsOnly(PAT);
    }

    @Test
    void aCredentialWithoutTheTypeHeaderOrWithAnotherSpellingIsRefused() throws Exception {
        assertThat(rawGetIssue("user-secret")).as("control: the exact type passes").contains("\"key\":\"ABC-1\"");
        BACKEND.reset();

        for (String type : new String[] {null, "User-Secret", "unknown"}) {
            String answer = rawGetIssue(type);

            assertThat(answer).as(String.valueOf(type)).contains("internal_error").doesNotContain("\"key\":\"ABC-1\"");
        }
        assertThat(BACKEND.requests()).as("refused before the policy, the resolvers and Jira").isEmpty();
    }

    private String rawGetIssue(String type) throws Exception {
        java.net.http.HttpClient http = java.net.http.HttpClient.newHttpClient();
        java.util.function.Function<String, java.net.http.HttpRequest.Builder> request = body ->
                java.net.http.HttpRequest.newBuilder(java.net.URI.create(baseUrl() + ENDPOINT))
                        .header("Content-Type", "application/json")
                        .header("Accept", "application/json, text/event-stream")
                        .POST(java.net.http.HttpRequest.BodyPublishers.ofString(body));
        java.net.http.HttpRequest.Builder init = request.apply("""
                {"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-06-18",
                "capabilities":{},"clientInfo":{"name":"type-test","version":"1"}}}""");
        GATEWAY.user().username("jkowalski").groups(java.util.List.of(READ_GROUP)).mint().headers().forEach(init::header);
        java.net.http.HttpResponse<String> initialized = http.send(init.build(), java.net.http.HttpResponse.BodyHandlers.ofString());
        String session = initialized.headers().firstValue("Mcp-Session-Id").orElseThrow();
        java.net.http.HttpRequest.Builder ready = request.apply("{\"jsonrpc\":\"2.0\",\"method\":\"notifications/initialized\"}")
                .header("Mcp-Session-Id", session);
        GATEWAY.user().username("jkowalski").groups(java.util.List.of(READ_GROUP)).mint().headers().forEach(ready::header);
        http.send(ready.build(), java.net.http.HttpResponse.BodyHandlers.ofString());
        java.net.http.HttpRequest.Builder call = request.apply("""
                {"jsonrpc":"2.0","id":2,"method":"tools/call","params":{"name":"get_issue","arguments":{"issueKey":"ABC-1"}}}""")
                .header("Mcp-Session-Id", session).header("X-MCP-Credential", PAT);
        if (type != null) {
            call.header("X-MCP-Credential-Type", type);
        }
        GATEWAY.user().username("jkowalski").groups(java.util.List.of(READ_GROUP)).tool("get_issue").mint().headers()
                .forEach(call::header);
        return http.send(call.build(), java.net.http.HttpResponse.BodyHandlers.ofString()).body();
    }

    private McpSyncClient clientWithType(BackendCredential.Type type) {
        HttpClientStreamableHttpTransport transport = HttpClientStreamableHttpTransport.builder(baseUrl())
                .endpoint(ENDPOINT)
                .openConnectionOnStartup(false)
                .httpRequestCustomizer(GATEWAY.asGateway(ServerTestSupport::editor, PAT, type))
                .build();
        McpSyncClient client = McpClient.sync(transport).requestTimeout(Duration.ofSeconds(20)).build();
        client.initialize();
        return client;
    }
}
