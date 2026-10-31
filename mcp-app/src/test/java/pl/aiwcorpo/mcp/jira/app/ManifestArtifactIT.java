package pl.aiwcorpo.mcp.jira.app;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Runs after package (failsafe): the file of the build, {@code mcp-app/target/mcp-manifest.json}, is what the server
 * publishes at {@code GET /mcp-manifest}. The test context overrides the version and the address of the manifest, so
 * those two are left out of the comparison. The file is written by {@link ManifestExport} from the production
 * configuration without starting the server.
 */
class ManifestArtifactIT extends ServerTestSupport {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    @Test
    void theBuildArtifactIsTheManifestTheServerPublishes() throws Exception {
        HttpResponse<String> served = HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(URI.create(baseUrl() + "/mcp-manifest"))
                        .headers(GATEWAY.service().mint().headers().entrySet().stream()
                                .flatMap(e -> java.util.stream.Stream.of(e.getKey(), e.getValue()))
                                .toArray(String[]::new))
                        .GET().build(),
                HttpResponse.BodyHandlers.ofString());
        JsonNode artifact = JSON.readTree(Files.readString(MANIFEST_FILE));

        JsonNode servedManifest = JSON.readTree(served.body());

        assertThat(withoutVersionAndAddress(artifact)).isEqualTo(withoutVersionAndAddress(servedManifest));
        // Same credential types, so the definitionHash values of the file and of the server are comparable too.
        assertThat(artifact.at("/credential/acceptedTypes")).isEqualTo(servedManifest.at("/credential/acceptedTypes"));
        assertThat(artifact.get("tools")).as("definitions and definitionHash").isEqualTo(servedManifest.get("tools"));
        assertThat(artifact.has("environment")).as("only the devstack profile publishes environment=dev (CC-138)").isFalse();
        assertThat(artifact.get("version").asString()).as("the version of the pom, not the 1.0.0 of the tests")
                .isNotEqualTo("1.0.0").isEqualTo(projectVersion());
        assertThat(artifact.at("/credential/acceptedTypes").toString()).isEqualTo("[\"user-secret\"]");
    }

    /** The version in the parent pom (the failsafe working directory is mcp-app). */
    private static String projectVersion() throws java.io.IOException {
        String pom = Files.readString(java.nio.file.Path.of("..", "pom.xml"));
        java.util.regex.Matcher version = java.util.regex.Pattern
                .compile("<artifactId>mcp-jira-parent</artifactId>\\s*<version>([^<]+)</version>").matcher(pom);
        assertThat(version.find()).as("version in the parent pom").isTrue();
        return version.group(1);
    }

    private static JsonNode withoutVersionAndAddress(JsonNode manifest) {
        ObjectNode copy = (ObjectNode) manifest.deepCopy();
        copy.remove("version");
        copy.remove("internalUrl");
        return copy;
    }
}
