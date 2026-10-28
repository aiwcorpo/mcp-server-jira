package pl.aiwcorpo.mcp.jira.integration;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.net.URI;
import java.net.http.HttpClient;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.ssl.SslBundles;
import org.springframework.core.env.Environment;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;
import pl.aiwcorpo.mcp.jira.core.domain.IssueSearch;
import pl.aiwcorpo.mcp.jira.core.domain.IssueSearchResult;
import pl.aiwcorpo.mcp.jira.core.domain.IssueTransition;
import pl.aiwcorpo.mcp.jira.core.domain.JiraComment;
import pl.aiwcorpo.mcp.jira.core.domain.JiraIssue;
import pl.aiwcorpo.mcp.jira.core.domain.JiraProject;
import pl.aiwcorpo.mcp.jira.core.domain.RejectedByJiraException;
import pl.aiwcorpo.mcp.jira.core.domain.RejectedByJiraException.Operation;
import pl.aiwcorpo.mcp.jira.core.port.out.JiraResourcePort;
import pl.aiwcorpo.mcp.platform.security.BackendCredential;
import pl.aiwcorpo.mcp.platform.security.CredentialContext;
import pl.aiwcorpo.mcp.platform.security.CredentialRequiredException;

/**
 * Calls the Jira Data Center REST API v2 as the user: with the PAT the gateway resolved for this request
 * (C3, {@code Authorization: Bearer}), never with an account of the server.
 *
 * <p>Error mapping matters for security: 404 and 403 both become "not visible" (one client-facing code,
 * {@code not_found_or_denied}), and 401 means the stored PAT no longer works, so the user is sent to the
 * portal to reconnect ({@code credential_required}). A failed call becomes an exception with the status
 * alone, an unusable answer (redirect, empty or unparsable body, oversized body, record without its key) a
 * {@link BackendResponseException}, so no response body reaches a message or a log line.
 */
@Component
@EnableConfigurationProperties(JiraBackendProperties.class)
public class JiraResourceAdapter implements JiraResourcePort {

    /** System id of this server: {@code server-id} is {@code mcp-<system>}, and the starter refuses any other. */
    static final String SYSTEM = "jira";

    /** Fields asked from Jira; everything else (comments, links, custom fields, rendered HTML) is not fetched. */
    private static final String ISSUE_FIELDS = "summary,description,status,issuetype,assignee,project,updated";

    private static final List<String> SEARCH_FIELDS =
            List.of("summary", "status", "issuetype", "assignee", "project", "updated");

    private static final String DEV_PROFILE = "dev";
    private static final Logger LOG = LoggerFactory.getLogger(JiraResourceAdapter.class);

    private final RestClient client;

    public JiraResourceAdapter(JiraBackendProperties properties, ObjectProvider<SslBundles> sslBundles,
            Environment environment) {
        this.client = RestClient.builder()
                .baseUrl(requireBaseUrl(properties.baseUrl(),
                        environment.matchesProfiles(DEV_PROFILE) || properties.allowPlainHttp()))
                .requestFactory(requestFactory(properties, sslBundles))
                .requestInterceptor(new BoundedBodyInterceptor(properties.maxResponseSize().toBytes()))
                .defaultStatusHandler(status -> status.isError() || status.is3xxRedirection(), (request, response) -> {
                    // Status only: the body and headers of a failed call may echo the credential or carry backend
                    // detail, and the exception message ends up in the starter's tool_failed log line.
                    HttpStatusCode status = response.getStatusCode();
                    if (status.is3xxRedirection()) {
                        // Redirects are not followed (the credential must not go elsewhere) and an empty body
                        // must not read as "no issue" or as a transition that happened: an SSO login page
                        // would turn into not_found_or_denied or into a false success.
                        throw new BackendResponseException("redirect " + status.value());
                    }
                    throw status.is4xxClientError()
                            ? HttpClientErrorException.create(status, "", HttpHeaders.EMPTY, new byte[0], null)
                            : HttpServerErrorException.create(status, "", HttpHeaders.EMPTY, new byte[0], null);
                })
                .build();
    }

    @Override
    public Optional<JiraProject> findProject(String projectKey) {
        return asUser(() -> client.get()
                .uri("/rest/api/2/project/{key}", projectKey)
                .headers(credential()::applyTo)
                .retrieve()
                .body(ProjectResponse.class))
                .map(ProjectResponse::toDomain);
    }

    @Override
    public Optional<JiraIssue> findIssue(String issueKey) {
        return asUser(() -> client.get()
                .uri("/rest/api/2/issue/{key}?fields=" + ISSUE_FIELDS, issueKey)
                .headers(credential()::applyTo)
                .retrieve()
                .body(IssueResponse.class))
                .map(IssueResponse::toDomain);
    }

    @Override
    public IssueSearchResult search(IssueSearch search, int maxResults) {
        Map<String, Object> request = Map.of(
                "jql", search.jql(),
                "startAt", 0,
                "maxResults", maxResults,
                "fields", SEARCH_FIELDS,
                "validateQuery", "strict");
        try {
            SearchResponse response = asUser(() -> client.post()
                    .uri("/rest/api/2/search")
                    .headers(credential()::applyTo)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(request)
                    .retrieve()
                    .body(SearchResponse.class))
                    .orElseThrow(() -> new BackendResponseException("search not available"));
            return response.toDomain();
        } catch (HttpClientErrorException.BadRequest unknownValue) {
            throw new RejectedByJiraException(Operation.SEARCH);
        }
    }

    @Override
    public Optional<JiraComment> addComment(String issueKey, String body) {
        try {
            return asUser(() -> client.post()
                    .uri("/rest/api/2/issue/{key}/comment", issueKey)
                    .headers(credential()::applyTo)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(Map.of("body", body))
                    .retrieve()
                    .body(CommentResponse.class))
                    .map(comment -> comment.toDomain(issueKey));
        } catch (HttpClientErrorException.BadRequest rejected) {
            throw new RejectedByJiraException(Operation.COMMENT);
        }
    }

    @Override
    public Optional<List<IssueTransition>> transitions(String issueKey) {
        return asUser(() -> client.get()
                .uri("/rest/api/2/issue/{key}/transitions", issueKey)
                .headers(credential()::applyTo)
                .retrieve()
                .body(TransitionsResponse.class))
                .map(TransitionsResponse::toDomain);
    }

    @Override
    public boolean transition(String issueKey, String transitionId) {
        try {
            return asUser(() -> client.post()
                    .uri("/rest/api/2/issue/{key}/transitions", issueKey)
                    .headers(credential()::applyTo)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(Map.of("transition", Map.of("id", transitionId)))
                    .retrieve()
                    .toBodilessEntity())
                    .map(response -> {
                        // Jira answers a done transition with 204; any other 2xx is not a confirmation.
                        if (response.getStatusCode().value() != 204) {
                            throw new BackendResponseException("transition answered " + response.getStatusCode().value());
                        }
                        return true;
                    })
                    .orElse(false);
        } catch (HttpClientErrorException.BadRequest rejected) {
            throw new RejectedByJiraException(Operation.TRANSITION);
        }
    }

    @Override
    public boolean deleteIssue(String issueKey) {
        try {
            return asUser(() -> client.delete()
                    .uri("/rest/api/2/issue/{key}", issueKey)
                    .headers(credential()::applyTo)
                    .retrieve()
                    .toBodilessEntity())
                    .map(response -> {
                        // Jira answers a deletion with 204; any other 2xx is not a confirmation.
                        if (response.getStatusCode().value() != 204) {
                            throw new BackendResponseException("delete answered " + response.getStatusCode().value());
                        }
                        return true;
                    })
                    .orElse(false);
        } catch (HttpClientErrorException.BadRequest rejected) {
            throw new RejectedByJiraException(Operation.DELETE);
        }
    }

    private static BackendCredential credential() {
        return CredentialContext.require(SYSTEM);
    }

    /**
     * Runs one Jira call; 404 and 403 -> empty, 401 -> the user has to reconnect the account. A call that
     * returns nothing (an empty 2xx body) is the backend's fault, not "not visible".
     */
    private static <T> Optional<T> asUser(Supplier<T> call) {
        T result;
        try {
            result = call.get();
        } catch (HttpClientErrorException.NotFound | HttpClientErrorException.Forbidden hidden) {
            return Optional.empty();
        } catch (HttpClientErrorException.Unauthorized expired) {
            throw new CredentialRequiredException(SYSTEM);
        } catch (RestClientResponseException | ResourceAccessException statusOrIo) {
            // Status-only exceptions (see the status handler) and I/O failures: the starter maps 502-504 and
            // unreachable backends to upstream_unavailable.
            throw statusOrIo;
        } catch (RestClientException unreadable) {
            // A 2xx body that does not parse: the converter's message quotes the body.
            throw new BackendResponseException("body not readable");
        }
        if (result == null) {
            throw new BackendResponseException("empty body");
        }
        return Optional.of(result);
    }

    private static String requireBaseUrl(String baseUrl, boolean plainHttpAllowed) {
        if (baseUrl == null || baseUrl.isBlank()) {
            throw new IllegalStateException("jira.backend.base-url (MCP_JIRA_URL) must point at the Jira base URL");
        }
        URI uri = URI.create(baseUrl);
        if (uri.getHost() == null || !("https".equals(uri.getScheme()) || "http".equals(uri.getScheme()))) {
            throw new IllegalStateException("jira.backend.base-url must be an absolute http(s) URL");
        }
        if (!"https".equals(uri.getScheme())) {
            if (!plainHttpAllowed) {
                // The user's PAT travels in a header; over http it would cross the network in clear text.
                throw new IllegalStateException("jira.backend.base-url must use https outside the 'dev' profile "
                        + "(jira.backend.allow-plain-http exists for the mocks of mcp-platform-dev only)");
            }
            LOG.warn("event=backend_plain_http detail=\"credentials go to the backend unencrypted\"");
        }
        return baseUrl;
    }

    private static JdkClientHttpRequestFactory requestFactory(JiraBackendProperties properties,
            ObjectProvider<SslBundles> sslBundles) {
        // Redirects are not followed: the credential header must not travel to wherever a backend points.
        HttpClient.Builder http = HttpClient.newBuilder()
                .connectTimeout(properties.connectTimeout())
                .followRedirects(HttpClient.Redirect.NEVER);
        if (properties.sslBundle() != null && !properties.sslBundle().isBlank()) {
            http.sslContext(sslBundles.getObject().getBundle(properties.sslBundle()).createSslContext());
        }
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(http.build());
        factory.setReadTimeout(properties.readTimeout());
        return factory;
    }

    // Jira's representations; unknown fields are dropped here and never reach the model.

    @JsonIgnoreProperties(ignoreUnknown = true)
    record Named(String name) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record ProjectResponse(String key, Named projectCategory) {

        JiraProject toDomain() {
            if (key == null || key.isBlank()) {
                throw new BackendResponseException("project without key");
            }
            return new JiraProject(key, projectCategory == null || projectCategory.name() == null
                    ? "" : projectCategory.name());
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record ProjectRef(String key) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record IssueFields(String summary, String description, Named status, Named issuetype, Named assignee,
            ProjectRef project, String updated) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record IssueResponse(String key, IssueFields fields) {

        /** The key Jira reports; the write goes to it, so a record without one is not usable. */
        String requireKey() {
            if (key == null || key.isBlank()) {
                throw new BackendResponseException("issue without key");
            }
            return key;
        }

        JiraIssue toDomain() {
            IssueFields f = fields == null
                    ? new IssueFields(null, null, null, null, null, null, null) : fields;
            return new JiraIssue(requireKey(), f.project() == null ? null : f.project().key(), name(f.issuetype()),
                    name(f.status()), name(f.assignee()), f.updated(), f.summary(), f.description(), null);
        }

        private static String name(Named named) {
            return named == null ? null : named.name();
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record SearchResponse(List<IssueResponse> issues) {

        IssueSearchResult toDomain() {
            if (issues == null) {
                throw new BackendResponseException("search without issues");
            }
            return new IssueSearchResult(issues.stream().map(IssueResponse::toDomain).toList());
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record CommentResponse(String id, String created) {

        JiraComment toDomain(String issueKey) {
            if (id == null || id.isBlank()) {
                throw new BackendResponseException("comment without id");
            }
            return new JiraComment(issueKey, id, created);
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record Transition(String id, String name, Named to) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record TransitionsResponse(List<Transition> transitions) {

        List<IssueTransition> toDomain() {
            if (transitions == null) {
                throw new BackendResponseException("transitions missing");
            }
            return transitions.stream().map(t -> {
                if (t.id() == null || t.id().isBlank() || t.name() == null) {
                    throw new BackendResponseException("transition without id or name");
                }
                return new IssueTransition(t.id(), t.name(), t.to() == null ? null : t.to().name());
            }).toList();
        }
    }
}
