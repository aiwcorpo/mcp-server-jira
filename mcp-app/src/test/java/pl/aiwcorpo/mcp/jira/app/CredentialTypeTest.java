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
