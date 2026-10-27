package pl.aiwcorpo.mcp.jira.integration;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Where the backend system is and how long the server waits for it.
 *
 * @param baseUrl        base URL of the backend API ({@code MCP_JIRA_URL}); the server does not start without it
 * @param sslBundle      Spring SSL bundle trusted for an HTTPS backend (internal CA); empty = JDK default trust
 * @param connectTimeout time to open the connection
 * @param readTimeout    time for the whole response; the gateway gives up on the call soon after anyway
 */
@ConfigurationProperties("jira.backend")
public record JiraBackendProperties(String baseUrl, String sslBundle, Duration connectTimeout, Duration readTimeout) {

    public JiraBackendProperties {
        connectTimeout = connectTimeout == null ? Duration.ofSeconds(2) : connectTimeout;
        readTimeout = readTimeout == null ? Duration.ofSeconds(10) : readTimeout;
    }
}
