package pl.aiwcorpo.mcp.jira.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.modelcontextprotocol.client.McpSyncClient;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import pl.aiwcorpo.mcp.jira.integration.JiraCapabilityProbe;
import pl.aiwcorpo.mcp.platform.capability.Capabilities;
import pl.aiwcorpo.mcp.platform.capability.Capabilities.State;
import tools.jackson.databind.JsonNode;

/**
 * The same tools against the responses of two Data Center versions (fixtures {@code jira-dc-9.12} and
 * {@code jira-dc-11.3}, written from the documented response shapes, not recorded from an instance), and the probe
 * against the answers {@code serverInfo} can give.
 */
class VersionFixturesTest extends ServerTestSupport {

    @Autowired
    JiraCapabilityProbe probe;

    @BeforeEach
    void resetBackend() {
        BACKEND.reset();
    }

    @AfterEach
    void backToTheDefaultVersion() throws Exception {
        BACKEND.useDefaultFixtures();
        probe.probe();
    }

    @ParameterizedTest
    @ValueSource(strings = {"9.12", "11.3"})
    void theReadToolsGiveTheSameAnswerOnEveryVersion(String version) {
        BACKEND.useFixtures(version);
        try (McpSyncClient client = clientWithCredential(PAT)) {
            JsonNode project = json(call(client, "get_project", Map.of("project", "ABC")));
            assertThat(project.at("/issueTypes")).extracting(JsonNode::asString).containsExactly("Task", "Bug", "Sub-task");
            assertThat(project.at("/components")).hasSize(2);
            assertThat(project.at("/versions")).hasSize(2);

            JsonNode comments = json(call(client, "list_comments", Map.of("issueKey", "ABC-1")));
            assertThat(comments.at("/items")).hasSize(2);
            assertThat(comments.at("/items/0/author").asString()).isEqualTo("alice");

            JsonNode worklogs = json(call(client, "list_worklogs", Map.of("issueKey", "ABC-1")));
            assertThat(worklogs.at("/items/0/timeSpent").asString()).isEqualTo("1h 30m");

            JsonNode users = json(call(client, "find_assignable_users", Map.of("project", "ABC", "query", "bob")));
            assertThat(users.at("/users")).hasSize(1);
            assertThat(users.at("/users/0/username").asString()).isEqualTo("bob");
            assertThat(users.toString()).doesNotContain("avatar", "bob@corp.test");

            JsonNode projects = json(call(client, "list_projects", Map.of()));
            assertThat(projects.at("/items")).extracting(item -> item.get("key").asString()).containsExactly("ABC", "DEF");
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"9.12", "11.3"})
    void theProbeSupportsBothVersions(String version) throws Exception {
        BACKEND.useFixtures(version);

        Capabilities found = probe.probe();

        assertThat(found.state()).isEqualTo(State.SUPPORTED);
        assertThat(found.backendVersion()).startsWith(version.equals("9.12") ? "9.12" : "11.3");
        assertThat(found.features()).contains("issue_read", "issue_write", "search_offset");
        assertThat(BACKEND.requests()).extracting(StubBackend.Request::authorization).containsOnly("null");
    }

    @Test
    void theProbeRunsWithoutACredentialAndHitsOnlyServerInfo() throws Exception {
        probe.probe();

        assertThat(BACKEND.requests()).extracting(StubBackend.Request::path).containsExactly("/rest/api/2/serverInfo");
    }

    @Test
    void aVersionNobodyTestedIsDegraded() throws Exception {
        for (String version : new String[] {"8.14.0", "9.11.9", "12.0.0"}) {
            BACKEND.serverInfoAnswers(200, "{\"version\":\"" + version + "\",\"deploymentType\":\"Server\"}");
            assertThat(probe.probe().state()).as(version).isEqualTo(State.DEGRADED);
        }
        BACKEND.serverInfoAnswers(200, "{\"version\":\"1001.0.0-SNAPSHOT\",\"deploymentType\":\"Cloud\"}");
        assertThat(probe.probe().state()).isEqualTo(State.DEGRADED);
    }

    @Test
    void theVersionMayComeAsNumbers() throws Exception {
        BACKEND.serverInfoAnswers(200, "{\"versionNumbers\":[10,3,5],\"deploymentType\":\"Server\"}");

        assertThat(probe.probe().backendVersion()).isEqualTo("10.3.5");
        assertThat(probe.probe().state()).isEqualTo(State.SUPPORTED);
    }

    @Test
    void anAnswerThatIsNotAVersionIsUnavailableOrFailsTheProbe() throws Exception {
        BACKEND.serverInfoAnswers(200, "{\"serverTitle\":\"Corp Jira\"}");
        assertThat(probe.probe().state()).isEqualTo(State.UNAVAILABLE);

        BACKEND.serverInfoAnswers(200, "<html>SSO login</html>");
        assertThatThrownBy(probe::probe).isInstanceOf(RuntimeException.class);
        BACKEND.serverInfoAnswers(401, "{}");
        assertThatThrownBy(probe::probe).isInstanceOf(RuntimeException.class);
        BACKEND.serverInfoAnswers(500, "{}");
        assertThatThrownBy(probe::probe).isInstanceOf(RuntimeException.class);
    }
}
