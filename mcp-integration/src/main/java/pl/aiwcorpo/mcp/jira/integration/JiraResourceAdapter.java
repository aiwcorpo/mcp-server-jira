package pl.aiwcorpo.mcp.jira.integration;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.net.URI;
import java.net.http.HttpClient;
import java.util.ArrayList;
import java.util.LinkedHashMap;
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
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;
import pl.aiwcorpo.mcp.jira.core.domain.CommentPage;
import pl.aiwcorpo.mcp.jira.core.domain.IssueChanges;
import pl.aiwcorpo.mcp.jira.core.domain.IssueComment;
import pl.aiwcorpo.mcp.jira.core.domain.IssueSearch;
import pl.aiwcorpo.mcp.jira.core.domain.IssueSearchResult;
import pl.aiwcorpo.mcp.jira.core.domain.IssueTransition;
import pl.aiwcorpo.mcp.jira.core.domain.JiraAttachment;
import pl.aiwcorpo.mcp.jira.core.domain.JiraComment;
import pl.aiwcorpo.mcp.jira.core.domain.JiraIssue;
import pl.aiwcorpo.mcp.jira.core.domain.JiraProject;
import pl.aiwcorpo.mcp.jira.core.domain.JiraUser;
import pl.aiwcorpo.mcp.jira.core.domain.JiraWorklog;
import pl.aiwcorpo.mcp.jira.core.domain.NewIssue;
import pl.aiwcorpo.mcp.jira.core.domain.ProjectDetails;
import pl.aiwcorpo.mcp.jira.core.domain.ProjectSummary;
import pl.aiwcorpo.mcp.jira.core.domain.RejectedByJiraException;
import pl.aiwcorpo.mcp.jira.core.domain.RejectedByJiraException.Operation;
import pl.aiwcorpo.mcp.jira.core.domain.ServerInfo;
import pl.aiwcorpo.mcp.jira.core.domain.WorklogPage;
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
    public ServerInfo serverInfo() {
        // No credential on purpose: the probe runs outside any request and reads what Jira tells everybody.
        ServerInfoResponse response = client.get().uri("/rest/api/2/serverInfo").retrieve().body(ServerInfoResponse.class);
        if (response == null) {
            throw new BackendResponseException("empty body");
        }
        return response.toDomain();
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
                "startAt", search.startAt(),
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
            return response.toDomain(search.startAt());
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
                            // The issue may be gone (a proxy that turns 204 into 200): the client gets an error, the
                            // log says the operation is unconfirmed rather than failed.
                            LOG.warn("event=delete_unconfirmed status={}", response.getStatusCode().value());
                            throw new BackendResponseException("delete answered " + response.getStatusCode().value());
                        }
                        return true;
                    })
                    .orElse(false);
        } catch (HttpClientErrorException.BadRequest rejected) {
            throw new RejectedByJiraException(Operation.DELETE);
        }
    }

    @Override
    public Optional<ProjectDetails> projectDetails(String projectKey) {
        return asUser(() -> client.get()
                .uri("/rest/api/2/project/{key}", projectKey)
                .headers(credential()::applyTo)
                .retrieve()
                .body(ProjectDetailsResponse.class))
                .map(ProjectDetailsResponse::toDomain);
    }

    @Override
    public List<ProjectSummary> listProjects() {
        ProjectListItem[] items = asUser(() -> client.get()
                .uri("/rest/api/2/project")
                .headers(credential()::applyTo)
                .retrieve()
                .body(ProjectListItem[].class))
                .orElseThrow(() -> new BackendResponseException("project list not available"));
        List<ProjectSummary> projects = new ArrayList<>();
        for (ProjectListItem item : items) {
            projects.add(item.toDomain());
        }
        return projects;
    }

    @Override
    public Optional<List<JiraUser>> assignableUsers(String projectKey, String query, int maxResults) {
        try {
            return asUser(() -> client.get()
                    .uri("/rest/api/2/user/assignable/search?project={key}&username={query}&maxResults={max}",
                            projectKey, query, maxResults)
                    .headers(credential()::applyTo)
                    .retrieve()
                    .body(UserResponse[].class))
                    .map(users -> {
                        List<JiraUser> found = new ArrayList<>();
                        for (UserResponse user : users) {
                            found.add(user.toDomain());
                        }
                        return found;
                    });
        } catch (HttpClientErrorException.BadRequest unknownProject) {
            return Optional.empty();
        }
    }

    @Override
    public Optional<CommentPage> listComments(String issueKey, int startAt, int maxResults) {
        return asUser(() -> client.get()
                .uri("/rest/api/2/issue/{key}/comment?startAt={start}&maxResults={max}&orderBy=created",
                        issueKey, startAt, maxResults)
                .headers(credential()::applyTo)
                .retrieve()
                .body(CommentListResponse.class))
                .map(list -> list.toDomain(startAt));
    }

    @Override
    public Optional<JiraComment> editComment(String issueKey, String commentId, String body) {
        try {
            return asUser(() -> client.put()
                    .uri("/rest/api/2/issue/{key}/comment/{id}", issueKey, commentId)
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
    public Optional<String> createIssue(NewIssue issue) {
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("project", Map.of("key", issue.projectKey()));
        fields.put("issuetype", Map.of("name", issue.issueType()));
        fields.put("summary", issue.summary());
        if (issue.description() != null) {
            fields.put("description", issue.description());
        }
        if (!issue.labels().isEmpty()) {
            fields.put("labels", issue.labels());
        }
        if (issue.priority() != null) {
            fields.put("priority", Map.of("name", issue.priority()));
        }
        if (issue.assignee() != null) {
            fields.put("assignee", Map.of("name", issue.assignee()));
        }
        try {
            return asUser(() -> client.post()
                    .uri("/rest/api/2/issue")
                    .headers(credential()::applyTo)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(Map.of("fields", fields))
                    .retrieve()
                    .body(CreatedIssueResponse.class))
                    .map(CreatedIssueResponse::requireKey);
        } catch (HttpClientErrorException.BadRequest rejected) {
            throw new RejectedByJiraException(Operation.CREATE);
        }
    }

    @Override
    public boolean updateIssue(String issueKey, IssueChanges changes) {
        Map<String, Object> fields = new LinkedHashMap<>();
        if (changes.summary() != null) {
            fields.put("summary", changes.summary());
        }
        if (changes.description() != null) {
            fields.put("description", changes.description());
        }
        if (changes.labels() != null) {
            fields.put("labels", changes.labels());
        }
        if (changes.priority() != null) {
            fields.put("priority", Map.of("name", changes.priority()));
        }
        try {
            return expectNoContent(asUser(() -> client.put()
                    .uri("/rest/api/2/issue/{key}", issueKey)
                    .headers(credential()::applyTo)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(Map.of("fields", fields))
                    .retrieve()
                    .toBodilessEntity()), 204, "update");
        } catch (HttpClientErrorException.BadRequest rejected) {
            throw new RejectedByJiraException(Operation.UPDATE);
        }
    }

    @Override
    public boolean assign(String issueKey, String username) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("name", username);
        try {
            return expectNoContent(asUser(() -> client.put()
                    .uri("/rest/api/2/issue/{key}/assignee", issueKey)
                    .headers(credential()::applyTo)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(body)
                    .retrieve()
                    .toBodilessEntity()), 204, "assign");
        } catch (HttpClientErrorException.BadRequest rejected) {
            throw new RejectedByJiraException(Operation.ASSIGN);
        }
    }

    @Override
    public boolean linkIssues(String linkType, String inwardIssueKey, String outwardIssueKey) {
        try {
            return expectNoContent(asUser(() -> client.post()
                    .uri("/rest/api/2/issueLink")
                    .headers(credential()::applyTo)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(Map.of("type", Map.of("name", linkType),
                            "inwardIssue", Map.of("key", inwardIssueKey),
                            "outwardIssue", Map.of("key", outwardIssueKey)))
                    .retrieve()
                    .toBodilessEntity()), 201, "link");
        } catch (HttpClientErrorException.BadRequest rejected) {
            throw new RejectedByJiraException(Operation.LINK);
        }
    }

    @Override
    public Optional<JiraWorklog> addWorklog(String issueKey, String timeSpent, String comment) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("timeSpent", timeSpent);
        if (comment != null) {
            body.put("comment", comment);
        }
        try {
            return asUser(() -> client.post()
                    .uri("/rest/api/2/issue/{key}/worklog", issueKey)
                    .headers(credential()::applyTo)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(body)
                    .retrieve()
                    .body(WorklogResponse.class))
                    .map(WorklogResponse::toDomain);
        } catch (HttpClientErrorException.BadRequest rejected) {
            throw new RejectedByJiraException(Operation.WORKLOG);
        }
    }

    @Override
    public Optional<WorklogPage> listWorklogs(String issueKey, int startAt, int maxResults) {
        return asUser(() -> client.get()
                .uri("/rest/api/2/issue/{key}/worklog?startAt={start}&maxResults={max}", issueKey, startAt, maxResults)
                .headers(credential()::applyTo)
                .retrieve()
                .body(WorklogListResponse.class))
                .map(list -> list.toDomain(startAt));
    }

    @Override
    public Optional<List<JiraAttachment>> listAttachments(String issueKey) {
        return asUser(() -> client.get()
                .uri("/rest/api/2/issue/{key}?fields=attachment", issueKey)
                .headers(credential()::applyTo)
                .retrieve()
                .body(AttachmentIssueResponse.class))
                .map(AttachmentIssueResponse::toDomain);
    }

    /** Jira confirms these writes with a bodiless success of one exact status; any other 2xx is not a confirmation. */
    private static boolean expectNoContent(Optional<ResponseEntity<Void>> answer, int status,
            String operation) {
        return answer.map(response -> {
            if (response.getStatusCode().value() != status) {
                throw new BackendResponseException(operation + " answered " + response.getStatusCode().value());
            }
            return true;
        }).orElse(false);
    }

    /** The offset of the next page, or {@code null} when this one reached {@code total}. */
    static Integer nextStartAt(int startAt, int returned, Integer total) {
        return returned > 0 && total != null && startAt + returned < total ? startAt + returned : null;
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
    record SearchResponse(List<IssueResponse> issues, Integer total) {

        IssueSearchResult toDomain(int startAt) {
            if (issues == null) {
                throw new BackendResponseException("search without issues");
            }
            return new IssueSearchResult(issues.stream().map(IssueResponse::toDomain).toList(),
                    nextStartAt(startAt, issues.size(), total));
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

    @JsonIgnoreProperties(ignoreUnknown = true)
    record ServerInfoResponse(String version, String deploymentType, List<Integer> versionNumbers) {

        ServerInfo toDomain() {
            String text = version;
            if ((text == null || text.isBlank()) && versionNumbers != null && !versionNumbers.isEmpty()) {
                text = String.join(".", versionNumbers.stream().map(String::valueOf).toList());
            }
            return new ServerInfo(text, deploymentType);
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record NamedWithRelease(String name, boolean released) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record ProjectDetailsResponse(String key, String name, Named projectCategory, List<Named> issueTypes,
            List<Named> components, List<NamedWithRelease> versions) {

        ProjectDetails toDomain() {
            if (key == null || key.isBlank()) {
                throw new BackendResponseException("project without key");
            }
            return new ProjectDetails(key, name == null ? "" : name,
                    projectCategory == null || projectCategory.name() == null ? "" : projectCategory.name(),
                    names(issueTypes), names(components),
                    (versions == null ? List.<NamedWithRelease>of() : versions).stream()
                            .filter(v -> v.name() != null)
                            .map(v -> new ProjectDetails.Version(v.name(), v.released())).toList());
        }

        private static List<String> names(List<Named> named) {
            return (named == null ? List.<Named>of() : named).stream().map(Named::name)
                    .filter(n -> n != null).toList();
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record ProjectListItem(String key, String name, Named projectCategory) {

        ProjectSummary toDomain() {
            if (key == null || key.isBlank()) {
                throw new BackendResponseException("project without key");
            }
            return new ProjectSummary(key, name == null ? "" : name,
                    projectCategory == null || projectCategory.name() == null ? "" : projectCategory.name());
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record UserResponse(String name, String displayName, boolean active) {

        JiraUser toDomain() {
            if (name == null || name.isBlank()) {
                throw new BackendResponseException("user without name");
            }
            return new JiraUser(name, displayName == null ? "" : displayName, active);
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record CommentEntry(String id, UserResponse author, String body, String created, String updated) {

        IssueComment toDomain() {
            if (id == null || id.isBlank()) {
                throw new BackendResponseException("comment without id");
            }
            return new IssueComment(id, author == null ? null : author.name(), created, updated, body);
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record CommentListResponse(List<CommentEntry> comments, Integer total) {

        CommentPage toDomain(int startAt) {
            if (comments == null) {
                throw new BackendResponseException("comments missing");
            }
            return new CommentPage(comments.stream().map(CommentEntry::toDomain).toList(),
                    nextStartAt(startAt, comments.size(), total));
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record CreatedIssueResponse(String key) {

        String requireKey() {
            if (key == null || key.isBlank()) {
                throw new BackendResponseException("created issue without key");
            }
            return key;
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record WorklogResponse(String id, UserResponse author, String started, String timeSpent, String comment) {

        JiraWorklog toDomain() {
            if (id == null || id.isBlank()) {
                throw new BackendResponseException("worklog without id");
            }
            return new JiraWorklog(id, author == null ? null : author.name(), started, timeSpent, comment);
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record WorklogListResponse(List<WorklogResponse> worklogs, Integer total) {

        WorklogPage toDomain(int startAt) {
            if (worklogs == null) {
                throw new BackendResponseException("worklogs missing");
            }
            return new WorklogPage(worklogs.stream().map(WorklogResponse::toDomain).toList(),
                    nextStartAt(startAt, worklogs.size(), total));
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record AttachmentEntry(String id, String filename, Long size, String mimeType, String created, UserResponse author) {

        JiraAttachment toDomain() {
            if (id == null || id.isBlank()) {
                throw new BackendResponseException("attachment without id");
            }
            return new JiraAttachment(id, filename == null ? "" : filename, size == null ? -1 : size, mimeType,
                    created, author == null ? null : author.name());
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record AttachmentFields(List<AttachmentEntry> attachment) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record AttachmentIssueResponse(String key, AttachmentFields fields) {

        List<JiraAttachment> toDomain() {
            if (key == null || key.isBlank()) {
                throw new BackendResponseException("issue without key");
            }
            if (fields == null || fields.attachment() == null) {
                return List.of();
            }
            return fields.attachment().stream().map(AttachmentEntry::toDomain).toList();
        }
    }
}
