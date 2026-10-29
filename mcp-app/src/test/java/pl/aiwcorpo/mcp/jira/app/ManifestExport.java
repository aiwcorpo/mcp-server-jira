package pl.aiwcorpo.mcp.jira.app;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import pl.aiwcorpo.mcp.platform.test.TestGateway;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Writes the manifest the pipeline registers at the gateway (CC-133, CC-140) to the path given as the argument. It runs
 * in the package phase of every build, also with {@code -DskipTests}, and starts the real application with the
 * configuration of {@code application.yml}: the version is the one of the pom, the address and the credential types
 * are those of the configuration (or of {@code MCP_INTERNAL_URL} and {@code MCP_ACCEPTED_CREDENTIAL_TYPES} when set).
 * Only what the server cannot start without is stood in for: the gateway keys and the policy bundle (TestGateway) and
 * the Jira backend. The file is then compared with what the running server serves, so a build whose artifact differs
 * from the published manifest fails.
 */
public final class ManifestExport {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private ManifestExport() {
    }

    public static void main(String[] args) {
        try {
            export(Path.of(args[0]));
        } catch (Throwable failure) {
            failure.printStackTrace();
            System.exit(1);
        }
        System.exit(0);
    }

    private static void export(Path target) throws Exception {
        Path file = target.toAbsolutePath();
        Files.deleteIfExists(file);
        StubBackend backend = StubBackend.start();
        try (TestGateway gateway = TestGateway.start(ServerTestSupport.SERVER_ID)) {
            Map<String, Object> properties = new HashMap<>(gateway.serverProperties());
            properties.keySet().removeIf(name -> name.startsWith("aiwcorpo.mcp.platform.manifest."));
            properties.put("aiwcorpo.mcp.platform.manifest.output-file", file.toString());
            properties.put("jira.backend.base-url", backend.baseUrl());
            properties.put("server.port", "0");
            fromEnvironment(properties, "MCP_INTERNAL_URL", "aiwcorpo.mcp.platform.manifest.internal-url");
            fromEnvironment(properties, "MCP_ACCEPTED_CREDENTIAL_TYPES",
                    "aiwcorpo.mcp.platform.manifest.accepted-credential-types");
            try (ConfigurableApplicationContext context = new SpringApplicationBuilder(McpServerApplication.class)
                    .profiles("dev").run(arguments(properties))) {
                String port = context.getEnvironment().getProperty("local.server.port");
                HttpResponse<String> served = HttpClient.newHttpClient().send(
                        HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/mcp-manifest"))
                                .headers(flatten(gateway.service().mint().headers())).GET().build(),
                        HttpResponse.BodyHandlers.ofString());
                JsonNode artifact = JSON.readTree(Files.readString(file));
                if (served.statusCode() != 200 || !artifact.equals(JSON.readTree(served.body()))) {
                    throw new IllegalStateException("the manifest file differs from GET /mcp-manifest");
                }
                if (artifact.has("environment")) {
                    throw new IllegalStateException("a build artifact must not carry environment (devstack only)");
                }
            }
        }
    }

    /** Command-line arguments outrank application-dev.yml, which the TestGateway values must override. */
    private static String[] arguments(Map<String, Object> properties) {
        return properties.entrySet().stream().map(e -> "--" + e.getKey() + "=" + e.getValue()).toArray(String[]::new);
    }

    private static void fromEnvironment(Map<String, Object> properties, String variable, String property) {
        String value = System.getenv(variable);
        if (value != null && !value.isBlank()) {
            properties.put(property, value);
        }
    }

    private static String[] flatten(Map<String, String> headers) {
        return headers.entrySet().stream().flatMap(e -> java.util.stream.Stream.of(e.getKey(), e.getValue()))
                .toArray(String[]::new);
    }
}
