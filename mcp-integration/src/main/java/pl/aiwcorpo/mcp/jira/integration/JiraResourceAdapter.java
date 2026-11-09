package pl.aiwcorpo.mcp.jira.integration;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import tools.jackson.core.JacksonException;
import tools.jackson.core.JsonParser;
import tools.jackson.core.JsonToken;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
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
import pl.aiwcorpo.mcp.jira.core.domain.AttachmentText;
import pl.aiwcorpo.mcp.jira.core.domain.BoardPage;
import pl.aiwcorpo.mcp.jira.core.domain.ChangelogEntry;
import pl.aiwcorpo.mcp.jira.core.domain.ChangelogPage;
import pl.aiwcorpo.mcp.jira.core.domain.CreateFields;
import pl.aiwcorpo.mcp.jira.core.domain.CurrentUser;
import pl.aiwcorpo.mcp.jira.core.domain.IssueLink;
import pl.aiwcorpo.mcp.jira.core.domain.IssueLinkEnds;
import pl.aiwcorpo.mcp.jira.core.domain.IssueTypeInfo;
import pl.aiwcorpo.mcp.jira.core.domain.IssueTypePage;
import pl.aiwcorpo.mcp.jira.core.domain.IssueTypeStatuses;
import pl.aiwcorpo.mcp.jira.core.domain.JiraBoard;
import pl.aiwcorpo.mcp.jira.core.domain.JiraSprint;
import pl.aiwcorpo.mcp.jira.core.domain.LinkType;
import pl.aiwcorpo.mcp.jira.core.domain.SprintPage;
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
import pl.aiwcorpo.mcp.jira.core.domain.ProjectList;
import pl.aiwcorpo.mcp.jira.core.domain.ProjectSummary;
import pl.aiwcorpo.mcp.jira.core.domain.RejectedByJiraException;
import pl.aiwcorpo.mcp.jira.core.domain.RejectedByJiraException.Operation;
import pl.aiwcorpo.mcp.jira.core.domain.ServerInfo;
import pl.aiwcorpo.mcp.jira.core.domain.WorklogPage;
import pl.aiwcorpo.mcp.jira.core.port.out.JiraResourcePort;
import pl.aiwcorpo.mcp.platform.security.BackendCredential;
import pl.aiwcorpo.mcp.platform.security.CredentialContext;
import pl.aiwcorpo.mcp.platform.security.CredentialRequiredException;
import pl.aiwcorpo.mcp.platform.text.TextHygiene;
import pl.aiwcorpo.mcp.platform.text.UntrustedText;

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
    private static final String ISSUE_FIELDS = "summary,description,status,issuetype,assignee,project,updated,issuelinks";

    private static final List<String> SEARCH_FIELDS =
            List.of("summary", "status", "issuetype", "assignee", "project", "updated");

    /** The {@code self} link of a comment: {@code .../issue/<issue id>/comment/<comment id>}. */
    private static final java.util.regex.Pattern COMMENT_SELF =
            java.util.regex.Pattern.compile("/issue/([0-9]+)/comment/([0-9]+)$");

    private static final JsonMapper PROJECT_JSON = JsonMapper.builder().build();
    private static final java.util.regex.Pattern ID = java.util.regex.Pattern.compile("[0-9]{1,18}");
    private static final java.util.regex.Pattern ISSUE_KEY =
            java.util.regex.Pattern.compile("[A-Z][A-Z0-9_]{1,9}-[1-9][0-9]{0,9}");

    /** Attachments larger than this are not downloaded; the model gets the reason instead. */
    static final long MAX_ATTACHMENT_BYTES = 200_000;
    /** Most allowed values of one create field that are kept; the tool shows fewer and says when there were more. */
    private static final int MAX_ALLOWED_VALUES = 100;
    private static final Set<String> TEXT_APPLICATION_TYPES = Set.of("application/json", "application/xml");
    private static final java.util.regex.Pattern FIELD_ID = java.util.regex.Pattern.compile("[A-Za-z0-9_.-]{1,60}");
    private static final java.util.regex.Pattern PROJECT_KEY = java.util.regex.Pattern.compile("[A-Z][A-Z0-9_]{1,9}");

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
                .defaultStatusHandler(status -> status.isError() || status.is3xxRedirection(),
                        (request, response) -> failFor(response.getStatusCode()))
                .build();
    }

    /**
     * Status only: the body and headers of a failed call may echo the credential or carry backend detail, and the
     * exception message ends up in the starter's tool_failed log line.
     */
    private static void failFor(HttpStatusCode status) {
        if (status.is3xxRedirection()) {
            // Redirects are not followed (the credential must not go elsewhere) and an empty body must not read as
            // "no issue" or as a transition that happened: an SSO login page would turn into not_found_or_denied or
            // into a false success.
            throw new BackendResponseException("redirect " + status.value());
        }
        throw status.is4xxClientError()
                ? HttpClientErrorException.create(status, "", HttpHeaders.EMPTY, new byte[0], null)
                : HttpServerErrorException.create(status, "", HttpHeaders.EMPTY, new byte[0], null);
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
                .body(ProjectDetailsResponse.class))
                .map(ProjectDetailsResponse::toProject);
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
    public ProjectList listProjects(int limit) {
        // The list is not paged, so it is read as a stream and dropped after `limit` entries: an instance with
        // thousands of projects costs `limit` entries, not the whole body (and not the size limit of the response).
        return asUser(() -> client.get()
                .uri("/rest/api/2/project")
                .headers(credential()::applyTo)
                .exchange((request, response) -> {
                    if (response.getStatusCode().isError() || response.getStatusCode().is3xxRedirection()) {
                        failFor(response.getStatusCode());
                    }
                    return readProjects(response.getBody(), limit);
                }))
                .orElseThrow(() -> new BackendResponseException("project list not available"));
    }

    private static ProjectList readProjects(InputStream body, int limit) {
        try (JsonParser parser = PROJECT_JSON.createParser(body)) {
            if (parser.nextToken() != JsonToken.START_ARRAY) {
                throw new BackendResponseException("project list is not an array");
            }
            List<ProjectSummary> projects = new ArrayList<>();
            while (parser.nextToken() == JsonToken.START_OBJECT) {
                if (projects.size() >= limit) {
                    return new ProjectList(projects, true);
                }
                projects.add(parser.readValueAs(ProjectListItem.class).toDomain());
            }
            return new ProjectList(projects, false);
        } catch (JacksonException unreadable) {
            throw new BackendResponseException("project list not readable");
        }
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
        // The PUT path names the issue, but a comment id alone is what Jira looks up: confirm, as the user, that the
        // comment hangs under the issue the policy judged before anything is written.
        Optional<CommentSelfResponse> own = ownComment(issueKey, commentId);
        if (own.isEmpty()) {
            return Optional.empty();
        }
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("body", body);
        // A PUT without `visibility` drops the restriction of a comment, which would show it to everybody who sees the
        // issue. The restriction read above is sent back as it was; one this server cannot reproduce stops the edit.
        CommentVisibility visibility = own.get().visibility();
        if (visibility != null) {
            if (!visibility.reproducible()) {
                throw new RejectedByJiraException(Operation.RESTRICTED_COMMENT);
            }
            request.put("visibility", visibility.toRequest());
        }
        try {
            return asUser(() -> client.put()
                    .uri("/rest/api/2/issue/{key}/comment/{id}", issueKey, commentId)
                    .headers(credential()::applyTo)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(request)
                    .retrieve()
                    .body(CommentResponse.class))
                    .map(comment -> comment.toDomain(issueKey));
        } catch (HttpClientErrorException.BadRequest rejected) {
            throw new RejectedByJiraException(Operation.COMMENT);
        }
    }

    /** The comment, when it hangs under the issue; empty when either is not visible or they do not belong together. */
    private Optional<CommentSelfResponse> ownComment(String issueKey, String commentId) {
        Optional<IssueIdResponse> issue = asUser(() -> client.get()
                .uri("/rest/api/2/issue/{key}?fields=summary", issueKey)
                .headers(credential()::applyTo)
                .retrieve()
                .body(IssueIdResponse.class));
        Optional<CommentSelfResponse> comment = asUser(() -> client.get()
                .uri("/rest/api/2/issue/{key}/comment/{id}", issueKey, commentId)
                .headers(credential()::applyTo)
                .retrieve()
                .body(CommentSelfResponse.class));
        if (issue.isEmpty() || comment.isEmpty() || issue.get().id() == null || comment.get().self() == null
                || !commentId.equals(comment.get().id())) {
            return Optional.empty();
        }
        java.util.regex.Matcher link = COMMENT_SELF.matcher(comment.get().self());
        boolean belongs = link.find() && link.group(1).equals(issue.get().id()) && link.group(2).equals(commentId);
        return belongs ? comment : Optional.empty();
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

    @Override
    public Optional<ChangelogPage> changelog(String issueKey, int startAt, int maxResults) {
        // Jira Data Center has no paged changelog endpoint on every supported version, so the history comes with the
        // issue (expand=changelog, nothing else of the issue) and the page is cut here.
        return asUser(() -> client.get()
                .uri("/rest/api/2/issue/{key}?fields=summary&expand=changelog", issueKey)
                .headers(credential()::applyTo)
                .retrieve()
                .body(ChangelogIssueResponse.class))
                .map(response -> response.toDomain(startAt, maxResults));
    }

    @Override
    public Optional<List<IssueTypeStatuses>> projectStatuses(String projectKey) {
        return asUser(() -> client.get()
                .uri("/rest/api/2/project/{key}/statuses", projectKey)
                .headers(credential()::applyTo)
                .retrieve()
                .body(StatusesOfTypeEntry[].class))
                .map(entries -> {
                    List<IssueTypeStatuses> found = new ArrayList<>();
                    for (StatusesOfTypeEntry entry : entries) {
                        found.add(entry.toDomain());
                    }
                    return found;
                });
    }

    @Override
    public Optional<IssueTypePage> issueTypes(String projectKey, int startAt, int maxResults) {
        try {
            return asUser(() -> client.get()
                    .uri("/rest/api/2/issue/createmeta/{key}/issuetypes?startAt={start}&maxResults={max}",
                            projectKey, startAt, maxResults)
                    .headers(credential()::applyTo)
                    .retrieve()
                    .body(IssueTypesMetaResponse.class))
                    .map(response -> response.toDomain(startAt));
        } catch (HttpClientErrorException.BadRequest unknownProject) {
            return Optional.empty();
        }
    }

    @Override
    public Optional<CreateFields> createFields(String projectKey, String issueTypeId, int maxFields) {
        try {
            return asUser(() -> client.get()
                    .uri("/rest/api/2/issue/createmeta/{key}/issuetypes/{type}?startAt=0&maxResults={max}",
                            projectKey, issueTypeId, maxFields)
                    .headers(credential()::applyTo)
                    .retrieve()
                    .body(CreateFieldsResponse.class))
                    .map(CreateFieldsResponse::toDomain);
        } catch (HttpClientErrorException.BadRequest unknownType) {
            return Optional.empty();
        }
    }

    @Override
    public List<LinkType> linkTypes() {
        return asUser(() -> client.get()
                .uri("/rest/api/2/issueLinkType")
                .headers(credential()::applyTo)
                .retrieve()
                .body(LinkTypesResponse.class))
                .orElseThrow(() -> new BackendResponseException("link types not available"))
                .toDomain();
    }

    @Override
    public CurrentUser currentUser() {
        return asUser(() -> client.get()
                .uri("/rest/api/2/myself")
                .headers(credential()::applyTo)
                .retrieve()
                .body(MyselfResponse.class))
                .orElseThrow(() -> new BackendResponseException("current user not available"))
                .toDomain();
    }

    @Override
    public Optional<AttachmentText> readAttachment(String issueKey, String attachmentId, int maxChars) {
        Optional<List<JiraAttachment>> attachments = listAttachments(issueKey);
        Optional<JiraAttachment> attachment = attachments.flatMap(list -> list.stream()
                .filter(candidate -> attachmentId.equals(candidate.id())).findFirst());
        if (attachment.isEmpty()) {
            return Optional.empty();
        }
        JiraAttachment file = attachment.get();
        if (!isTextMedia(file.mimeType())) {
            return Optional.of(new AttachmentText(file, "", false, "binary_attachment"));
        }
        if (file.size() < 0 || file.size() > MAX_ATTACHMENT_BYTES) {
            return Optional.of(new AttachmentText(file, "", false, "attachment_too_large"));
        }
        // The download address is built from the configured base URL and the id; the `content` link of Jira's answer
        // names whatever host Jira believes it has, and the credential must not go there.
        String name = file.filename().isBlank() || file.filename().equals(".") || file.filename().equals("..")
                ? "attachment" : file.filename();
        Optional<byte[]> data = asUser(() -> {
            byte[] bytes = client.get()
                    .uri("/secure/attachment/{id}/{name}", file.id(), name)
                    .accept(MediaType.ALL)
                    .headers(credential()::applyTo)
                    .retrieve()
                    .body(byte[].class);
            return bytes == null ? new byte[0] : bytes;
        });
        if (data.isEmpty()) {
            return Optional.empty();
        }
        String text = TextHygiene.normalize(new String(data.get(), StandardCharsets.UTF_8));
        String cut = UntrustedText.cut(text, maxChars);
        return Optional.of(new AttachmentText(file, cut, cut.length() < text.length(), null));
    }

    private static boolean isTextMedia(String mediaType) {
        if (mediaType == null) {
            return false;
        }
        String base = mediaType.split(";", 2)[0].strip().toLowerCase(Locale.ROOT);
        return base.startsWith("text/") || TEXT_APPLICATION_TYPES.contains(base);
    }

    @Override
    public Optional<BoardPage> boards(String projectKey, String type, int startAt, int maxResults) {
        try {
            return asUser(() -> (type == null
                    ? client.get().uri("/rest/agile/1.0/board?projectKeyOrId={key}&startAt={start}&maxResults={max}",
                            projectKey, startAt, maxResults)
                    : client.get().uri("/rest/agile/1.0/board?projectKeyOrId={key}&type={type}&startAt={start}&maxResults={max}",
                            projectKey, type, startAt, maxResults))
                    .headers(credential()::applyTo)
                    .retrieve()
                    .body(BoardsResponse.class))
                    .map(response -> response.toDomain(startAt));
        } catch (HttpClientErrorException.BadRequest unknownProject) {
            return Optional.empty();
        }
    }

    @Override
    public Optional<List<String>> boardProjectKeys(String boardId, int limit) {
        try {
            return asUser(() -> client.get()
                    .uri("/rest/agile/1.0/board/{id}/project?startAt=0&maxResults={max}", boardId, limit)
                    .headers(credential()::applyTo)
                    .retrieve()
                    .body(BoardProjectsResponse.class))
                    .flatMap(response -> response.toKeys(limit));
        } catch (HttpClientErrorException.BadRequest unknownBoard) {
            return Optional.empty();
        }
    }

    @Override
    public Optional<SprintPage> sprints(String boardId, String state, int startAt, int maxResults) {
        try {
            return asUser(() -> (state == null
                    ? client.get().uri("/rest/agile/1.0/board/{id}/sprint?startAt={start}&maxResults={max}",
                            boardId, startAt, maxResults)
                    : client.get().uri("/rest/agile/1.0/board/{id}/sprint?state={state}&startAt={start}&maxResults={max}",
                            boardId, state, startAt, maxResults))
                    .headers(credential()::applyTo)
                    .retrieve()
                    .body(SprintsResponse.class))
                    .map(response -> response.toDomain(startAt));
        } catch (HttpClientErrorException.BadRequest noSprints) {
            throw new RejectedByJiraException(Operation.SPRINTS);
        }
    }

    @Override
    public Optional<String> sprintBoardId(String sprintId) {
        return asUser(() -> client.get()
                .uri("/rest/agile/1.0/sprint/{id}", sprintId)
                .headers(credential()::applyTo)
                .retrieve()
                .body(SprintOriginResponse.class))
                .flatMap(response -> response.originBoardId() == null
                        ? Optional.empty() : Optional.of(requireId(String.valueOf(response.originBoardId()), "board")));
    }

    @Override
    public Optional<IssueSearchResult> sprintIssues(String sprintId, int startAt, int maxResults) {
        try {
            return asUser(() -> client.get()
                    .uri("/rest/agile/1.0/sprint/{id}/issue?startAt={start}&maxResults={max}&fields={fields}",
                            sprintId, startAt, maxResults, String.join(",", SEARCH_FIELDS))
                    .headers(credential()::applyTo)
                    .retrieve()
                    .body(SearchResponse.class))
                    .map(response -> response.toDomain(startAt));
        } catch (HttpClientErrorException.BadRequest unknownSprint) {
            return Optional.empty();
        }
    }

    @Override
    public boolean addWatcher(String issueKey, String username) {
        try {
            // Jira takes the bare JSON string as the body; a String handed to the client would be sent without quotes.
            return expectNoContent(asUser(() -> client.post()
                    .uri("/rest/api/2/issue/{key}/watchers", issueKey)
                    .headers(credential()::applyTo)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(PROJECT_JSON.writeValueAsString(username))
                    .retrieve()
                    .toBodilessEntity()), 204, "add watcher");
        } catch (HttpClientErrorException.BadRequest rejected) {
            throw new RejectedByJiraException(Operation.WATCHER);
        }
    }

    @Override
    public boolean removeWatcher(String issueKey, String username) {
        try {
            return expectNoContent(asUser(() -> client.delete()
                    .uri("/rest/api/2/issue/{key}/watchers?username={name}", issueKey, username)
                    .headers(credential()::applyTo)
                    .retrieve()
                    .toBodilessEntity()), 204, "remove watcher");
        } catch (HttpClientErrorException.BadRequest rejected) {
            throw new RejectedByJiraException(Operation.WATCHER);
        }
    }

    @Override
    public Optional<IssueLinkEnds> issueLink(String linkId) {
        return asUser(() -> client.get()
                .uri("/rest/api/2/issueLink/{id}", linkId)
                .headers(credential()::applyTo)
                .retrieve()
                .body(IssueLinkResponse.class))
                .map(IssueLinkResponse::toDomain);
    }

    @Override
    public boolean deleteIssueLink(String linkId) {
        try {
            return expectNoContent(asUser(() -> client.delete()
                    .uri("/rest/api/2/issueLink/{id}", linkId)
                    .headers(credential()::applyTo)
                    .retrieve()
                    .toBodilessEntity()), 204, "unlink");
        } catch (HttpClientErrorException.BadRequest rejected) {
            throw new RejectedByJiraException(Operation.UNLINK);
        }
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

    /** Ids of comments, work logs and attachments are numbers; anything else is not Jira's answer. */
    private static String requireId(String id, String what) {
        if (id == null || !ID.matcher(id).matches()) {
            throw new BackendResponseException(what + " without a numeric id");
        }
        return id;
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
                        + "(jira.backend.allow-plain-http exists for the mocks of the dev stack only)");
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
    record ProjectRef(String key) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record IssueFields(String summary, String description, Named status, Named issuetype, Named assignee,
            ProjectRef project, String updated, List<IssueLinkEntry> issuelinks) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record LinkTypeRef(String name, String inward, String outward) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record IssueLinkEntry(String id, LinkTypeRef type, LinkedIssueRef inwardIssue, LinkedIssueRef outwardIssue) {

        /** An entry Jira does not describe fully (no id, type or key of the other issue) is left out, not guessed. */
        Optional<IssueLink> toDomain() {
            boolean inward = inwardIssue != null && inwardIssue.key() != null;
            LinkedIssueRef other = inward ? inwardIssue : outwardIssue;
            if (id == null || !ID.matcher(id).matches() || type == null || type.name() == null || other == null
                    || other.key() == null || !ISSUE_KEY.matcher(other.key()).matches()) {
                return Optional.empty();
            }
            String sentence = inward ? type.inward() : type.outward();
            return Optional.of(new IssueLink(id, type.name(), inward ? "inward" : "outward", sentence, other.key()));
        }
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
                    ? new IssueFields(null, null, null, null, null, null, null, null) : fields;
            List<IssueLink> links = (f.issuelinks() == null ? List.<IssueLinkEntry>of() : f.issuelinks()).stream()
                    .flatMap(entry -> entry.toDomain().stream()).toList();
            return new JiraIssue(requireKey(), f.project() == null ? null : f.project().key(), name(f.issuetype()),
                    name(f.status()), name(f.assignee()), f.updated(), f.summary(), f.description(), null, links);
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
            return new JiraComment(issueKey, requireId(id, "comment"), created);
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
    record NamedWithRelease(String name, Boolean released) {
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
                            .map(v -> new ProjectDetails.Version(v.name(), Boolean.TRUE.equals(v.released()))).toList());
        }

        JiraProject toProject() {
            ProjectDetails details = toDomain();
            return new JiraProject(details.key(), details.category(), details);
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
    record UserResponse(String name, String displayName, Boolean active) {

        JiraUser toDomain() {
            if (name == null || name.isBlank()) {
                throw new BackendResponseException("user without name");
            }
            return new JiraUser(name, displayName == null ? "" : displayName, Boolean.TRUE.equals(active));
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record CommentEntry(String id, UserResponse author, String body, String created, String updated) {

        IssueComment toDomain() {
            return new IssueComment(requireId(id, "comment"), author == null ? null : author.name(), created, updated, body);
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
    record IssueIdResponse(String id) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record CommentSelfResponse(String id, String self, CommentVisibility visibility) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record CommentVisibility(String type, String value, String identifier) {

        /** A role or group restriction with a name: the two kinds the API of Jira Data Center accepts back. */
        boolean reproducible() {
            return ("role".equals(type) || "group".equals(type)) && value != null && !value.isBlank();
        }

        Map<String, Object> toRequest() {
            Map<String, Object> request = new LinkedHashMap<>();
            request.put("type", type);
            request.put("value", value);
            if (identifier != null && !identifier.isBlank()) {
                request.put("identifier", identifier);
            }
            return request;
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record CreatedIssueResponse(String key) {

        String requireKey() {
            if (key == null || !ISSUE_KEY.matcher(key).matches()) {
                throw new BackendResponseException("created issue without a usable key");
            }
            return key;
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record WorklogResponse(String id, UserResponse author, String started, String timeSpent, String comment) {

        JiraWorklog toDomain() {
            return new JiraWorklog(requireId(id, "worklog"), author == null ? null : author.name(), started, timeSpent, comment);
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
            return new JiraAttachment(requireId(id, "attachment"), filename == null ? "" : filename, size == null ? -1 : size, mimeType,
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

    /** Offset of the next page of an Agile answer, which says {@code isLast} and often has no total. */
    static Integer nextStartAt(int startAt, int returned, Integer total, Boolean isLast) {
        if (returned == 0 || Boolean.TRUE.equals(isLast)) {
            return null;
        }
        return total != null ? nextStartAt(startAt, returned, total) : Boolean.FALSE.equals(isLast) ? startAt + returned : null;
    }

    private static String requireText(String value, String what) {
        if (value == null || value.isBlank()) {
            throw new BackendResponseException(what + " without a name");
        }
        return value;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record HistoryAuthor(String displayName) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record HistoryItem(String field, @JsonProperty("fromString") String fromValue, @JsonProperty("toString") String toValue) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record History(String id, HistoryAuthor author, String created, List<HistoryItem> items) {

        ChangelogEntry toDomain() {
            List<ChangelogEntry.FieldChange> changes = new ArrayList<>();
            for (HistoryItem item : items == null ? List.<HistoryItem>of() : items) {
                changes.add(new ChangelogEntry.FieldChange(item.field(), item.fromValue(), item.toValue()));
            }
            return new ChangelogEntry(requireId(id, "change"), author == null ? null : author.displayName(), created, changes);
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record Changelog(List<History> histories) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record ChangelogIssueResponse(String key, Changelog changelog) {

        ChangelogPage toDomain(int startAt, int maxResults) {
            if (key == null || key.isBlank()) {
                throw new BackendResponseException("issue without key");
            }
            List<History> all = changelog == null || changelog.histories() == null ? List.of() : changelog.histories();
            int from = Math.min(startAt, all.size());
            int end = Math.min(all.size(), from + maxResults);
            return new ChangelogPage(all.subList(from, end).stream().map(History::toDomain).toList(),
                    end < all.size() ? end : null);
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record StatusCategoryRef(String name) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record StatusEntry(String name, StatusCategoryRef statusCategory) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record StatusesOfTypeEntry(String name, List<StatusEntry> statuses) {

        IssueTypeStatuses toDomain() {
            List<IssueTypeStatuses.Status> found = new ArrayList<>();
            for (StatusEntry status : statuses == null ? List.<StatusEntry>of() : statuses) {
                found.add(new IssueTypeStatuses.Status(requireText(status.name(), "status"),
                        status.statusCategory() == null ? null : status.statusCategory().name()));
            }
            return new IssueTypeStatuses(requireText(name, "issue type"), found);
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record IssueTypeEntry(String id, String name, Boolean subtask) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record IssueTypesMetaResponse(Integer total, @JsonProperty("isLast") Boolean last, List<IssueTypeEntry> values) {

        IssueTypePage toDomain(int startAt) {
            if (values == null) {
                throw new BackendResponseException("issue types missing");
            }
            return new IssueTypePage(values.stream().map(type -> new IssueTypeInfo(requireId(type.id(), "issue type"),
                    requireText(type.name(), "issue type"), Boolean.TRUE.equals(type.subtask()))).toList(),
                    nextStartAt(startAt, values.size(), total, last));
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record FieldSchema(String type, String items) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record CreateFieldEntry(String fieldId, String name, Boolean required, FieldSchema schema, List<JsonNode> allowedValues) {

        CreateFields.Field toDomain() {
            if (fieldId == null || !FIELD_ID.matcher(fieldId).matches()) {
                throw new BackendResponseException("field without a usable id");
            }
            List<CreateFields.AllowedValue> values = new ArrayList<>();
            for (JsonNode value : allowedValues == null ? List.<JsonNode>of() : allowedValues) {
                if (values.size() >= MAX_ALLOWED_VALUES) {
                    break;
                }
                String label = value.isObject() ? firstText(value, "name", "value") : null;
                if (label != null) {
                    values.add(new CreateFields.AllowedValue(value.path("id").asString(null), label));
                }
            }
            return new CreateFields.Field(fieldId, name == null ? fieldId : name, Boolean.TRUE.equals(required),
                    schema == null ? null : schema.type(), schema == null ? null : schema.items(), values);
        }

        private static String firstText(JsonNode node, String... names) {
            for (String candidate : names) {
                String text = node.path(candidate).asString(null);
                if (text != null && !text.isBlank()) {
                    return text;
                }
            }
            return null;
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record CreateFieldsResponse(Integer total, List<CreateFieldEntry> values) {

        CreateFields toDomain() {
            if (values == null) {
                throw new BackendResponseException("create fields missing");
            }
            return new CreateFields(values.stream().map(CreateFieldEntry::toDomain).toList(),
                    total != null && total > values.size());
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record LinkTypeEntry(String id, String name, String inward, String outward) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record LinkTypesResponse(List<LinkTypeEntry> issueLinkTypes) {

        List<LinkType> toDomain() {
            if (issueLinkTypes == null) {
                throw new BackendResponseException("link types missing");
            }
            return issueLinkTypes.stream().map(type -> new LinkType(type.id(), requireText(type.name(), "link type"),
                    type.inward(), type.outward())).toList();
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record MyselfResponse(String name, String key, String displayName, Boolean active) {

        CurrentUser toDomain() {
            if (name == null || name.isBlank()) {
                throw new BackendResponseException("user without name");
            }
            return new CurrentUser(name, key, displayName == null ? "" : displayName, Boolean.TRUE.equals(active));
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record BoardLocation(String projectKey) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record BoardEntry(Long id, String name, String type, BoardLocation location) {

        JiraBoard toDomain() {
            String projectKey = location == null || location.projectKey() == null
                    || !PROJECT_KEY.matcher(location.projectKey()).matches() ? null : location.projectKey();
            return new JiraBoard(requireId(id == null ? null : String.valueOf(id), "board"),
                    name == null ? "" : name, type, projectKey);
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record BoardsResponse(Integer total, @JsonProperty("isLast") Boolean last, List<BoardEntry> values) {

        BoardPage toDomain(int startAt) {
            if (values == null) {
                throw new BackendResponseException("boards missing");
            }
            return new BoardPage(values.stream().map(BoardEntry::toDomain).toList(),
                    nextStartAt(startAt, values.size(), total, last));
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record BoardProjectsResponse(Integer total, @JsonProperty("isLast") Boolean last, List<ProjectRef> values) {

        /**
         * Empty when the list may be incomplete: the caller judges a board by all of its projects, and a partial list
         * would let a board with an unseen restricted project through. {@code limit} is the number of keys asked for.
         */
        Optional<List<String>> toKeys(int limit) {
            if (values == null) {
                throw new BackendResponseException("board projects missing");
            }
            // Complete only when Jira says so: a response without isLast and total proves nothing.
            boolean complete = Boolean.TRUE.equals(last) && (total == null || total == values.size())
                    || !Boolean.FALSE.equals(last) && total != null && total == values.size();
            if (!complete || values.size() > limit - 1) {
                return Optional.empty();
            }
            return Optional.of(values.stream().map(project -> requireText(project.key(), "project")).toList());
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record SprintEntry(Long id, String name, String state, String startDate, String endDate, String completeDate, String goal) {

        JiraSprint toDomain() {
            return new JiraSprint(requireId(id == null ? null : String.valueOf(id), "sprint"), name == null ? "" : name,
                    state, startDate, endDate, completeDate, goal);
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record SprintsResponse(Integer total, @JsonProperty("isLast") Boolean last, List<SprintEntry> values) {

        SprintPage toDomain(int startAt) {
            if (values == null) {
                throw new BackendResponseException("sprints missing");
            }
            return new SprintPage(values.stream().map(SprintEntry::toDomain).toList(),
                    nextStartAt(startAt, values.size(), total, last));
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record SprintOriginResponse(Long originBoardId) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record LinkedIssueRef(String key) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record IssueLinkResponse(String id, LinkedIssueRef inwardIssue, LinkedIssueRef outwardIssue) {

        IssueLinkEnds toDomain() {
            if (inwardIssue == null || outwardIssue == null || inwardIssue.key() == null || outwardIssue.key() == null
                    || !ISSUE_KEY.matcher(inwardIssue.key()).matches() || !ISSUE_KEY.matcher(outwardIssue.key()).matches()) {
                throw new BackendResponseException("link without both issues");
            }
            return new IssueLinkEnds(requireId(id, "link"), inwardIssue.key(), outwardIssue.key());
        }
    }
}
