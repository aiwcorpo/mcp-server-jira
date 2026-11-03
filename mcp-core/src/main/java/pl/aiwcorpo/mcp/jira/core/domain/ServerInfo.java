package pl.aiwcorpo.mcp.jira.core.domain;

/**
 * What Jira says about itself without logging in ({@code GET /rest/api/2/serverInfo}). Both fields come from the
 * backend and are untrusted text; the probe cleans them before they reach a log.
 *
 * @param version        product version, e.g. {@code 9.12.3}; {@code null} when the answer has none
 * @param deploymentType {@code Server} for Data Center and Server, {@code Cloud} for Atlassian Cloud; {@code null} when absent
 */
public record ServerInfo(String version, String deploymentType) {
}
