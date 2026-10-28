package pl.aiwcorpo.mcp.jira.app;

import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport;
import io.modelcontextprotocol.spec.McpSchema;
import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.time.Duration;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import pl.aiwcorpo.mcp.platform.test.TestGateway;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The whole server over HTTP, with TestGateway in place of the gateway and {@link StubBackend} in place of
 * the backend system. Shared by the test classes so they run against one application context.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("dev")
abstract class ServerTestSupport {

    static final String SERVER_ID = "mcp-jira";
    static final String SYSTEM = "jira";
    /** True only for a server of a system outside the contracts' SystemId (a trial one); this server is real. */
    static final boolean TRIAL_SYSTEM = false;
    static final String ENDPOINT = "/mcp";
    static final Set<String> CONTRACT_SYSTEMS = contractSystems();
    static final String PAT = "Bearer jira-pat-of-jkowalski-7f3a";
    static final JsonMapper JSON = JsonMapper.builder().build();

    static final TestGateway GATEWAY = TestGateway.start(SERVER_ID);
    static final StubBackend BACKEND = StubBackend.start();

    @DynamicPropertySource
    static void environment(DynamicPropertyRegistry registry) {
        GATEWAY.serverProperties().forEach((name, value) -> registry.add(name, () -> value));
        registry.add("jira.backend.base-url", BACKEND::baseUrl);
        // Short limits so the timeout and size tests run quickly; StubBackend's slow and huge issues exceed them.
        registry.add("jira.backend.read-timeout", () -> "1s");
        registry.add("jira.backend.max-response-size", () -> "64KB");
    }

    @LocalServerPort
    int port;

    String baseUrl() {
        return "http://127.0.0.1:" + port;
    }

    static final String READ_GROUP = "mcp-jira-read";
    static final String WRITE_GROUP = "mcp-jira-write";
    static final String DESTRUCTIVE_GROUP = "mcp-jira-destructive";
    /** Maps to the scope {@code Jira::Project/<key>} in the static entities of the example bundle (ABC and DEF only). */
    static final String PROJECT_ABC_GROUP = "mcp-jira-proj-ABC";
    static final String PROJECT_DEF_GROUP = "mcp-jira-proj-DEF";

    /**
     * An MCP client of the usual caller, who may read and write in ABC and DEF, reaching the server the way the
     * gateway does, with {@code credential} as C3 header.
     */
    McpSyncClient clientWithCredential(String credential) {
        return clientWithToken(ServerTestSupport::editor, credential);
    }

    /** Reads everything the bundle lets a reader read; nothing is granted for changes. */
    static void reader(TestGateway.Token user) {
        user.username("jkowalski").groups(List.of(READ_GROUP));
    }

    /** Reads, and changes issues of ABC and DEF. */
    static void editor(TestGateway.Token user) {
        user.username("jkowalski").groups(List.of(READ_GROUP, WRITE_GROUP, PROJECT_ABC_GROUP, PROJECT_DEF_GROUP));
    }

    /** As {@link #editor}, plus the destructive group and a fresh second factor, which the example bundle requires. */
    static void eraser(TestGateway.Token user) {
        user.username("jkowalski")
                .groups(List.of(READ_GROUP, WRITE_GROUP, DESTRUCTIVE_GROUP, PROJECT_ABC_GROUP, PROJECT_DEF_GROUP))
                .mfaAgeSeconds(60);
    }

    /** As {@link #clientWithCredential}, with the caller's token shaped by {@code user} (claims of a link call). */
    McpSyncClient clientWithToken(Consumer<TestGateway.Token> user, String credential) {
        HttpClientStreamableHttpTransport transport = HttpClientStreamableHttpTransport.builder(baseUrl())
                .endpoint(ENDPOINT)
                .openConnectionOnStartup(false)
                .httpRequestCustomizer(GATEWAY.asGateway(user, credential))
                .build();
        McpSyncClient client = McpClient.sync(transport).requestTimeout(Duration.ofSeconds(20)).build();
        client.initialize();
        return client;
    }

    /**
     * The starter sends only C10 errors valid against {@code tool-error.schema.json}, whose {@code system} is the
     * closed {@code SystemId} list of the contracts. A trial system (outside the list) gets {@code internal_error}
     * instead; a real system must get the full error with the portal link, never {@code internal_error}.
     */
    static void assertCredentialRequired(McpSchema.CallToolResult result) {
        assertThat(result.isError()).isTrue();
        if (TRIAL_SYSTEM) {
            assertThat(CONTRACT_SYSTEMS).as("a trial system is outside SystemId").doesNotContain(SYSTEM);
            assertThat(text(result)).contains("\"error\":\"internal_error\"");
        } else {
            assertThat(text(result)).contains("\"error\":\"credential_required\"", "\"system\":\"" + SYSTEM + "\"",
                    "\"connectUrl\":\"http://localhost:18090/connections?system=" + SYSTEM + "\"");
        }
    }

    /** A real server's system is one of the contracts'; a trial one is not. */
    @Test
    void contractSystemIsKnown() {
        assertThat(CONTRACT_SYSTEMS.contains(SYSTEM)).isEqualTo(!TRIAL_SYSTEM);
    }

    private static Set<String> contractSystems() {
        try (InputStream schema = ServerTestSupport.class.getClassLoader()
                .getResourceAsStream("mcp-contracts/schemas/common.schema.json")) {
            Set<String> systems = new HashSet<>();
            JsonMapper.builder().build().readTree(schema).at("/$defs/SystemId/enum")
                    .forEach(system -> systems.add(system.asString()));
            return Set.copyOf(systems);
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }
    }

    static void assertNotFoundOrDenied(McpSchema.CallToolResult result) {
        assertThat(result.isError()).isTrue();
        assertThat(text(result)).contains("\"error\":\"not_found_or_denied\"");
    }

    /** A C10 {@code invalid_argument} (not the MCP SDK's schema text) with the server's own message. */
    static void assertInvalidArgument(McpSchema.CallToolResult result, String message) {
        assertThat(result.isError()).isTrue();
        assertThat(text(result)).contains("\"error\":\"invalid_argument\"", message);
    }

    McpSchema.Tool listedTool(String name) {
        try (McpSyncClient client = clientWithCredential(PAT)) {
            return client.listTools().tools().stream().filter(t -> t.name().equals(name)).findFirst().orElseThrow();
        }
    }

    static JsonNode json(McpSchema.CallToolResult result) {
        return JSON.readTree(text(result));
    }

    static McpSchema.CallToolResult call(McpSyncClient client, String tool, Map<String, Object> arguments) {
        return client.callTool(McpSchema.CallToolRequest.builder(tool).arguments(arguments).build());
    }

    static String text(McpSchema.CallToolResult result) {
        StringBuilder text = new StringBuilder();
        result.content().forEach(content -> {
            if (content instanceof McpSchema.TextContent t) {
                text.append(t.text());
            }
        });
        return text.toString();
    }
}
